/* ===========================================================================
   Flexiple Sourcing — the recruiter-facing half of the loop.

   No framework and no build step: one state object, small named render
   functions, and DOM built as nodes so nothing the model writes is ever
   interpreted as markup.

   Two kinds of filtering live here and they are deliberately different:
     • the SEARCH DEFINITION (rail) is a server round trip — it changes who is
       in the result set and is recorded in the session history;
     • the VIEW FILTERS (toolbar) are pure client state — instant, throwaway,
       and they never touch the search itself.
   =========================================================================== */

(() => {
    'use strict';

    const TOP_CANDIDATES_SHOWN = 5;
    const COMPANY_TYPES = ['startup', 'scaleup', 'enterprise', 'agency'];
    const YEAR_PRESETS = [
        {label: '0–3', min: 0, max: 3},
        {label: '4–7', min: 4, max: 7},
        {label: '8–12', min: 8, max: 12},
        {label: '10+', min: 10, max: null},
    ];
    const QUICK_FEEDBACK = [
        'Too junior overall — I want more experience.',
        'Prefer stronger startup experience.',
        'These are too generalist; I want deeper specialists.',
        'Widen the search, I need more candidates.',
    ];
    const PREVIEW_DEBOUNCE_MS = 250;

    const state = {
        sessionId: null,
        server: null,            // the last SearchStateResponse, exactly as returned
        draft: null,             // the recruiter's in-progress edits to filters and rubric
        verdicts: {},            // profile_id -> 'yes' | 'no'
        expanded: new Set(),     // profile ids whose full detail is open
        previousRanks: {},       // profile_id -> rank before the last refresh, for the ↑↓ badges
        selectedProfileId: null, // keyboard cursor
        showAllCandidates: false,
        busy: false,
        vocabulary: null,
        scoringMode: 'ai',
        view: {
            text: '',
            sort: 'score',
            locations: new Set(),
            companyTypes: new Set(),
            markedOnly: false,
        },
        builder: newEmptyFilters(),
    };

    const byId = (id) => document.getElementById(id);

    function newEmptyFilters() {
        return {
            skills: [],
            min_years_experience: null,
            max_years_experience: null,
            locations: [],
            company_types: [],
            past_company_types: [],
            exclude_company_types: [],
            skills_match_all: false,
            remote_ok: false,
        };
    }

    /* ------------------------------ tiny DOM helper ------------------------------ */

    function h(tag, props, ...children) {
        const node = document.createElement(tag);
        for (const [key, value] of Object.entries(props || {})) {
            if (value === null || value === undefined || value === false) continue;
            if (key === 'class') node.className = value;
            else if (key === 'text') node.textContent = value;
            else if (key === 'value') node.value = value;
            else if (key === 'checked') node.checked = Boolean(value);
            else if (key.startsWith('on')) node.addEventListener(key.slice(2).toLowerCase(), value);
            else node.setAttribute(key, value === true ? '' : String(value));
        }
        for (const child of children.flat()) {
            if (child === null || child === undefined || child === false) continue;
            node.append(child.nodeType ? child : document.createTextNode(String(child)));
        }
        return node;
    }

    function replaceChildren(container, ...nodes) {
        container.replaceChildren(...nodes.flat().filter(Boolean));
    }

    function debounce(action, delayMs) {
        let timer = null;
        return (...args) => {
            clearTimeout(timer);
            timer = setTimeout(() => action(...args), delayMs);
        };
    }

    /* --------------------------------- API calls -------------------------------- */

    async function callApi(path, body) {
        let response;
        try {
            response = await fetch(path, {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify(body),
            });
        } catch (networkFailure) {
            throw {code: 'NETWORK_ERROR', message: 'Could not reach the server. Is it still running?', retryable: true};
        }
        const payload = await response.json().catch(() => null);
        if (!response.ok) {
            throw payload && payload.code
                ? payload
                : {code: 'UNEXPECTED', message: 'The server returned an unexpected response.', retryable: true};
        }
        return payload;
    }

    /* ------------------------------ loading + progress --------------------------- */

    function showStagedLoading(stepLabels) {
        byId('empty-panel').hidden = true;
        byId('no-view-matches').hidden = true;
        byId('candidate-list').replaceChildren();
        byId('show-all-button').hidden = true;
        byId('loading-panel').hidden = false;
        renderLoadingSteps(stepLabels, 0);
        return {
            advanceTo: (activeIndex) => renderLoadingSteps(stepLabels, activeIndex),
            finish: () => { byId('loading-panel').hidden = true; },
        };
    }

    function renderLoadingSteps(stepLabels, activeIndex) {
        replaceChildren(byId('loading-steps'), stepLabels.map((label, index) => {
            const status = index < activeIndex ? 'is-done' : index === activeIndex ? 'is-active' : '';
            return h('li', {class: `step ${status}`}, h('span', {class: 'step__marker'}), h('span', {text: label}));
        }));
    }

    /* --------------------------------- feedback ---------------------------------- */

    const ERROR_TITLES = {
        LLM_NOT_CONFIGURED: 'The AI service is not set up',
        LLM_TIMEOUT: 'The AI service timed out',
        LLM_RATE_LIMITED: 'Rate limited by the AI service',
        LLM_UNAVAILABLE: 'The AI service is unavailable',
        INVALID_LLM_RESPONSE: 'We could not safely apply that change',
        SESSION_FROZEN: 'This search is frozen',
        SESSION_NOT_FOUND: 'That session has expired',
        NOTHING_TO_UNDO: 'Nothing left to undo',
        INVALID_REQUEST: 'Check that input',
        NETWORK_ERROR: 'Cannot reach the server',
    };

    function showAlert(failure, retryAction) {
        const isSoft = failure.code === 'INVALID_REQUEST' || failure.code === 'SESSION_FROZEN';
        replaceChildren(byId('alert-slot'), h('div', {class: `alert ${isSoft ? 'alert--warn' : ''}`, role: 'alert'},
            h('div', {class: 'alert__body'},
                h('p', {class: 'alert__title', text: ERROR_TITLES[failure.code] || 'Something went wrong'}),
                h('p', {class: 'alert__message', text: failure.message || ''}),
                h('div', {class: 'alert__actions'},
                    retryAction && failure.retryable !== false
                        ? h('button', {type: 'button', class: 'btn btn--small btn--primary', onclick: retryAction}, 'Try again')
                        : null,
                    h('button', {type: 'button', class: 'btn btn--small btn--ghost', onclick: clearAlert}, 'Dismiss')))));
    }

    function clearAlert() {
        byId('alert-slot').replaceChildren();
    }

    function showToast(message) {
        const toast = h('div', {class: 'toast'}, message);
        byId('toast-slot').append(toast);
        setTimeout(() => {
            toast.classList.add('toast--leaving');
            setTimeout(() => toast.remove(), 250);
        }, 2400);
    }

    /* ------------------------------- search flow -------------------------------- */

    async function startSearchFromFreeText(event) {
        event.preventDefault();
        const query = byId('query').value.trim();
        if (!query) {
            setLandingStatus('Describe who you are looking for first.', true);
            byId('query').focus();
            return;
        }

        setBusy(true);
        setLandingStatus('Understanding your search…');
        try {
            const parsed = await callApi('/api/search/parse', {query});
            adoptServerState(parsed);
            enterWorkspace();
            const loading = showStagedLoading([
                'Understanding your search',
                `Filtering ${parsed.talent_pool_size} profiles`,
                'Ranking candidates against the rubric',
            ]);
            loading.advanceTo(2);
            await rankCurrentSearchDefinition('ai', loading);
        } catch (failure) {
            setLandingStatus(`${ERROR_TITLES[failure.code] || 'Something went wrong'} — ${failure.message}`, true);
        } finally {
            setBusy(false);
        }
    }

    async function startSearchFromManualFilters() {
        setBusy(true);
        setLandingStatus('Searching the talent map…');
        try {
            adoptServerState(await callApi('/api/search/manual-start', {filters: state.builder}));
            enterWorkspace();
            showToast(`${state.server.matched_count} profiles matched — ranked instantly, no AI call`);
        } catch (failure) {
            setLandingStatus(`${ERROR_TITLES[failure.code] || 'Something went wrong'} — ${failure.message}`, true);
        } finally {
            setBusy(false);
        }
    }

    async function rankCurrentSearchDefinition(scoringMode, existingLoading) {
        const loading = existingLoading || showStagedLoading([scoringMode === 'local'
            ? 'Ranking by how well they match your filters'
            : 'Scoring every candidate against the fit rubric']);
        try {
            adoptServerState(await callApi('/api/search/run',
                {session_id: state.sessionId, scoring_mode: scoringMode}));
            clearAlert();
        } catch (failure) {
            showAlert(failure, () => runWithBusyGuard(() => rankCurrentSearchDefinition(scoringMode)));
        } finally {
            loading.finish();
            renderWorkspace();
        }
    }

    async function refineFromFeedback(event) {
        if (event) event.preventDefault();
        const feedback = byId('feedback').value.trim();
        const verdicts = Object.entries(state.verdicts).map(([profileId, verdict]) => ({profile_id: profileId, verdict}));
        if (!feedback && verdicts.length === 0) {
            showAlert({
                code: 'INVALID_REQUEST',
                message: 'Say what you think, or mark a few candidates good or not, and we will adjust the search.',
                retryable: false,
            });
            return;
        }

        const loading = showStagedLoading(['Updating the search based on your feedback']);
        await runWithBusyGuard(async () => {
            try {
                adoptServerState(await callApi('/api/search/refine',
                    {session_id: state.sessionId, feedback, verdicts}));
                byId('feedback').value = '';
                state.verdicts = {};
                clearAlert();
                showToast('Search refined — see what changed on the right');
            } catch (failure) {
                showAlert(failure, () => refineFromFeedback());
            } finally {
                loading.finish();
                renderWorkspace();
            }
        });
    }

    async function applySearchDefinitionEdits() {
        const loading = showStagedLoading(['Applying your changes and re-ranking']);
        await runWithBusyGuard(async () => {
            try {
                adoptServerState(await callApi('/api/search/manual-update', {
                    session_id: state.sessionId,
                    filters: state.draft.filters,
                    rubric: state.draft.rubric,
                    scoring_mode: state.scoringMode,
                }));
                clearAlert();
                showToast('Your edits are applied');
            } catch (failure) {
                showAlert(failure, applySearchDefinitionEdits);
            } finally {
                loading.finish();
                renderWorkspace();
            }
        });
    }

    async function undoLastRefinement() {
        if (!state.server || !state.server.can_undo) return;
        const loading = showStagedLoading(['Rolling back the last change']);
        await runWithBusyGuard(async () => {
            try {
                adoptServerState(await callApi('/api/search/undo',
                    {session_id: state.sessionId, scoring_mode: state.scoringMode}));
                clearAlert();
                showToast('Rolled back to the previous search');
            } catch (failure) {
                showAlert(failure, undoLastRefinement);
            } finally {
                loading.finish();
                renderWorkspace();
            }
        });
    }

    async function freezeSearch() {
        await runWithBusyGuard(async () => {
            try {
                adoptServerState(await callApi('/api/search/freeze', {session_id: state.sessionId}));
                clearAlert();
                renderWorkspace();
                byId('frozen-summary').scrollIntoView({behavior: 'smooth', block: 'start'});
            } catch (failure) {
                showAlert(failure, freezeSearch);
                renderWorkspace();
            }
        });
    }

    async function runWithBusyGuard(action) {
        if (state.busy) return;
        setBusy(true);
        try {
            await action();
        } finally {
            setBusy(false);
        }
    }

    function setBusy(isBusy) {
        state.busy = isBusy;
        byId('topbar-progress').hidden = !isBusy;
        byId('search-button').disabled = isBusy;
        byId('build-submit').disabled = isBusy;
        byId('refine-button').disabled = isBusy || isFrozen();
        byId('freeze-button').disabled = isBusy || isFrozen();
        byId('apply-button').disabled = isBusy || isFrozen();
        byId('undo-button').disabled = isBusy;
        byId('rank-ai').disabled = isBusy || isFrozen();
        byId('rank-local').disabled = isBusy || isFrozen();
    }

    /* ------------------------------ state plumbing ------------------------------ */

    /** Server state is the source of truth; the draft is reset to it after every successful call. */
    function adoptServerState(response) {
        if (state.server) {
            state.previousRanks = Object.fromEntries(
                state.server.results.map(candidate => [candidate.profile_id, candidate.rank]));
        }
        state.server = response;
        state.sessionId = response.session_id;
        state.scoringMode = response.scoring_mode || state.scoringMode;

        const filters = structuredClone(response.filters);
        // The API omits null fields, so make the two optional bounds explicit; otherwise "typed a
        // number then cleared it" would look like an unapplied edit forever.
        filters.min_years_experience = filters.min_years_experience ?? null;
        filters.max_years_experience = filters.max_years_experience ?? null;
        state.draft = {filters, rubric: structuredClone(response.rubric)};
        state.showAllCandidates = false;
    }

    function isFrozen() {
        return Boolean(state.server && state.server.frozen);
    }

    function hasUnappliedEdits() {
        if (!state.server || !state.draft) return false;
        return JSON.stringify(state.draft.filters) !== JSON.stringify(state.server.filters)
            || JSON.stringify(state.draft.rubric) !== JSON.stringify(state.server.rubric);
    }

    function enterWorkspace() {
        byId('landing').hidden = true;
        byId('workspace').hidden = false;
        renderWorkspace();
    }

    /* --------------------------------- rendering -------------------------------- */

    function renderWorkspace() {
        const data = state.server;
        if (!data) return;

        byId('topbar-query').textContent = data.original_query;
        byId('pool-size').textContent = data.talent_pool_size;

        const roundCount = data.history.length;
        const roundPill = byId('round-pill');
        roundPill.hidden = roundCount === 0;
        roundPill.textContent = roundCount === 1 ? 'Refined once' : `Refined ${roundCount} times`;

        byId('frozen-pill').hidden = !data.frozen;
        byId('freeze-button').hidden = data.frozen;
        byId('undo-button').hidden = !data.can_undo;
        byId('feedback').disabled = data.frozen;
        byId('refine-button').disabled = data.frozen || state.busy;

        renderRankingSwitch();
        renderResultsSection();
        renderFiltersPanel();
        renderRubricPanel();
        renderHistoryPanel();
        renderQuickFeedback();
        renderVerdictSummary();
        renderFrozenSummary();
    }

    function renderRankingSwitch() {
        const mode = state.server.scoring_mode;
        byId('rank-ai').classList.toggle('is-active', mode === 'ai');
        byId('rank-local').classList.toggle('is-active', mode === 'local');
        byId('rank-ai').disabled = state.busy || isFrozen();
        byId('rank-local').disabled = state.busy || isFrozen();
    }

    /* ----------------------- view filters (instant, client side) ----------------- */

    function candidatesAfterViewFilters() {
        const view = state.view;
        const needle = view.text.trim().toLowerCase();

        let visible = state.server.results.filter(candidate => {
            if (view.markedOnly && state.verdicts[candidate.profile_id] !== 'yes') return false;
            if (view.locations.size && !view.locations.has(candidate.location)) return false;
            if (view.companyTypes.size && !view.companyTypes.has(candidate.current_company_type)) return false;
            return !needle || candidateMatchesText(candidate, needle);
        });

        const comparators = {
            score: (a, b) => b.score - a.score || a.rank - b.rank,
            'years-desc': (a, b) => b.years_experience - a.years_experience || a.rank - b.rank,
            'years-asc': (a, b) => a.years_experience - b.years_experience || a.rank - b.rank,
            name: (a, b) => a.name.localeCompare(b.name),
            company: (a, b) => a.current_company.localeCompare(b.current_company),
        };
        return visible.sort(comparators[view.sort] || comparators.score);
    }

    function candidateMatchesText(candidate, needle) {
        return [candidate.name, candidate.current_title, candidate.current_company, candidate.location,
            candidate.education, candidate.profile_summary, candidate.skills.join(' '),
            (candidate.past_companies || []).map(company => company.company).join(' ')]
            .some(field => (field || '').toLowerCase().includes(needle));
    }

    function isViewFiltered() {
        const view = state.view;
        return Boolean(view.text.trim()) || view.locations.size > 0 || view.companyTypes.size > 0 || view.markedOnly;
    }

    function clearViewFilters() {
        state.view.text = '';
        state.view.locations.clear();
        state.view.companyTypes.clear();
        state.view.markedOnly = false;
        byId('result-search').value = '';
        renderResultsSection();
    }

    /* --------------------------------- results ---------------------------------- */

    function renderResultsSection() {
        const data = state.server;
        const meta = byId('results-meta');
        const list = byId('candidate-list');

        if (data.matched_count === 0) {
            meta.textContent = `0 of ${data.talent_pool_size} profiles match`;
            byId('toolbar').hidden = true;
            byId('no-view-matches').hidden = true;
            byId('show-all-button').hidden = true;
            list.replaceChildren();
            renderEmptyDiagnostics();
            return;
        }

        byId('empty-panel').hidden = true;
        byId('toolbar').hidden = false;
        renderFacets();

        const visible = candidatesAfterViewFilters();
        const shown = state.showAllCandidates ? visible : visible.slice(0, TOP_CANDIDATES_SHOWN);

        meta.textContent = describeResultCount(visible.length);
        byId('clear-view').hidden = !isViewFiltered();
        byId('marked-only').setAttribute('aria-pressed', String(state.view.markedOnly));

        if (visible.length === 0) {
            list.replaceChildren();
            byId('show-all-button').hidden = true;
            byId('no-view-matches').hidden = false;
            byId('no-view-matches-detail').textContent =
                `All ${data.matched_count} matching candidates are hidden by the filters on this view. `
                + 'These only change what you see, not the search itself.';
            return;
        }

        byId('no-view-matches').hidden = true;
        replaceChildren(list, shown.map((candidate, index) => renderCandidateCard(candidate, index)));
        animateScoreBars();

        const hiddenCount = visible.length - shown.length;
        byId('show-all-button').hidden = visible.length <= TOP_CANDIDATES_SHOWN;
        byId('show-all-button').textContent = state.showAllCandidates
            ? `Show top ${TOP_CANDIDATES_SHOWN} only`
            : `Show all ${visible.length} candidates (${hiddenCount} more)`;
    }

    function describeResultCount(visibleCount) {
        const data = state.server;
        const base = `${data.matched_count} of ${data.talent_pool_size} profiles match`;
        if (isViewFiltered()) {
            return `${base} · showing ${visibleCount} in this view`;
        }
        if (data.scoring_mode === 'local') {
            // Said plainly, because a column of identical 100s is the honest answer when the
            // filters cannot separate people - and the way out is one click away.
            return `${base} · scored by how many of your criteria each one meets`;
        }
        return data.scoring_mode === 'ai' ? `${base}, ranked against your fit rubric` : base;
    }

    /** The score bars start at zero and fill on the next frame, so a re-rank reads as movement. */
    function animateScoreBars() {
        requestAnimationFrame(() => {
            document.querySelectorAll('.score__fill').forEach(fill => {
                fill.style.width = `${Math.max(Number(fill.dataset.score), 3)}%`;
            });
        });
    }

    function renderFacets() {
        renderOneFacet('facet-locations', 'Location', 'locations', candidate => candidate.location);
        renderOneFacet('facet-company-types', 'Currently at', 'companyTypes',
            candidate => candidate.current_company_type);
    }

    function renderOneFacet(containerId, label, viewKey, valueOf) {
        const counts = new Map();
        state.server.results.forEach(candidate => {
            const value = valueOf(candidate);
            if (value) counts.set(value, (counts.get(value) || 0) + 1);
        });

        // A facet with a single option tells the recruiter nothing they cannot already see.
        if (counts.size < 2) {
            byId(containerId).replaceChildren();
            return;
        }

        const selected = state.view[viewKey];
        replaceChildren(byId(containerId),
            h('span', {class: 'facet__label', text: label}),
            [...counts.entries()].sort((a, b) => b[1] - a[1]).map(([value, count]) => h('button', {
                type: 'button',
                class: 'facet__option',
                'aria-pressed': String(selected.has(value)),
                onclick: () => {
                    selected.has(value) ? selected.delete(value) : selected.add(value);
                    state.showAllCandidates = false;
                    renderResultsSection();
                },
            }, value, ' ', h('span', {class: 'facet__count', text: count}))));
    }

    /* ---------------------------------- cards ----------------------------------- */

    function renderCandidateCard(candidate, positionInList) {
        const verdict = state.verdicts[candidate.profile_id];
        const matched = new Set(candidate.matched_skills || []);
        const needle = state.view.text.trim().toLowerCase();
        const isExpanded = state.expanded.has(candidate.profile_id);
        const isSelected = state.selectedProfileId === candidate.profile_id;

        const card = h('article', {
            class: ['card',
                verdict === 'yes' ? 'is-yes' : verdict === 'no' ? 'is-no' : '',
                isSelected ? 'is-selected' : ''].filter(Boolean).join(' '),
            'data-profile-id': candidate.profile_id,
            style: `animation-delay:${Math.min(positionInList * 40, 240)}ms`,
            onclick: () => { state.selectedProfileId = candidate.profile_id; },
        },
            h('div', {class: 'card__top'},
                renderRankBadge(candidate),
                h('div', {class: 'card__identity'},
                    h('p', {class: 'card__name', text: candidate.name}),
                    h('p', {class: 'card__title', text: candidate.current_title})),
                h('div', {class: 'score'},
                    h('p', {class: 'score__value', text: candidate.score}),
                    h('p', {
                        class: 'score__label',
                        text: state.server.scoring_mode === 'local' ? 'match' : 'fit',
                        title: state.server.scoring_mode === 'local'
                            ? 'Share of the criteria you set that this person meets'
                            : 'Judged against your fit rubric',
                    }),
                    h('div', {class: 'score__bar'},
                        h('div', {class: 'score__fill', 'data-score': candidate.score})))),

            h('div', {class: 'meta'},
                h('span', {class: 'chip', text: `${candidate.years_experience} yrs`}),
                h('span', {class: chipClassFor(candidate.location, needle), text: candidate.location}),
                h('span', {class: chipClassFor(candidate.current_company, needle), text: candidate.current_company}),
                h('span', {class: 'chip chip--type chip--muted', text: candidate.current_company_type})),

            h('div', {class: 'meta'},
                candidate.skills.map(skill => h('span', {
                    class: [matched.has(skill) ? 'chip chip--matched' : 'chip chip--muted',
                        needle && skill.toLowerCase().includes(needle) ? 'chip--hit' : ''].filter(Boolean).join(' '),
                    text: skill,
                }))),

            renderPastCompanies(candidate),

            h('div', {class: 'why'},
                h('p', {class: 'why__label', text: 'Why this matches'}),
                h('p', {class: 'why__text', text: candidate.explanation}),
                candidate.evidence && candidate.evidence.length
                    ? h('div', {class: 'evidence'}, candidate.evidence.map(item =>
                        h('span', {class: 'evidence__item'},
                            h('span', {class: 'evidence__field', text: `${item.field.replace(/_/g, ' ')}:`}),
                            h('span', {text: item.value}))))
                    : null),

            isExpanded ? renderCandidateDetail(candidate) : null,

            h('div', {class: 'card__actions'},
                renderVerdictButton(candidate, 'yes', 'Good match'),
                renderVerdictButton(candidate, 'no', 'Not a match'),
                h('span', {class: 'spacer'}),
                h('button', {
                    type: 'button',
                    class: 'link-button',
                    'aria-expanded': String(isExpanded),
                    onclick: () => toggleCandidateDetail(candidate.profile_id),
                }, isExpanded ? 'Hide detail' : 'Full profile')));

        return card;
    }

    function chipClassFor(value, needle) {
        return needle && (value || '').toLowerCase().includes(needle) ? 'chip chip--hit' : 'chip';
    }

    function renderRankBadge(candidate) {
        const previousRank = state.previousRanks[candidate.profile_id];
        const movement = previousRank === undefined ? null : previousRank - candidate.rank;
        return h('span', {class: 'rank'},
            h('span', {text: `#${candidate.rank}`}),
            movement === null && Object.keys(state.previousRanks).length
                ? h('span', {class: 'rank__delta rank__delta--new', text: 'new', title: 'New in these results'})
                : movement > 0
                    ? h('span', {class: 'rank__delta rank__delta--up', text: `▲${movement}`, title: `Was #${previousRank}`})
                    : movement < 0
                        ? h('span', {class: 'rank__delta rank__delta--down', text: `▼${-movement}`, title: `Was #${previousRank}`})
                        : null);
    }

    function renderPastCompanies(candidate) {
        const pastCompanies = (candidate.past_companies || []).slice(0, 3);
        if (!pastCompanies.length) return null;
        return h('p', {class: 'past'}, 'Previously ',
            pastCompanies.map((company, index) => h('span', {},
                index > 0 ? ', ' : '',
                h('strong', {text: company.company}),
                ` (${company.company_type}, ${company.years}y)`)));
    }

    function renderCandidateDetail(candidate) {
        const rows = [
            ['Summary', candidate.profile_summary],
            ['Education', candidate.education],
            ['All skills', candidate.skills.join(', ')],
            ['Full history', (candidate.past_companies || [])
                .map(company => `${company.title} at ${company.company} (${company.company_type}, ${company.years}y)`)
                .join(' · ')],
        ];
        return h('div', {class: 'detail'}, rows
            .filter(([, value]) => value)
            .map(([label, value]) => h('div', {class: 'detail__row'},
                h('span', {class: 'detail__label', text: label}),
                h('span', {class: 'detail__value', text: value}))));
    }

    function renderVerdictButton(candidate, verdictValue, label) {
        const isSelected = state.verdicts[candidate.profile_id] === verdictValue;
        return h('button', {
            type: 'button',
            class: 'verdict',
            'data-verdict': verdictValue,
            'aria-pressed': String(isSelected),
            disabled: isFrozen(),
            onclick: (event) => {
                event.stopPropagation();
                toggleVerdict(candidate.profile_id, verdictValue);
            },
        }, verdictValue === 'yes' ? '✓ ' : '✕ ', label);
    }

    function toggleVerdict(profileId, verdictValue) {
        if (state.verdicts[profileId] === verdictValue) {
            delete state.verdicts[profileId];
        } else {
            state.verdicts[profileId] = verdictValue;
        }
        state.selectedProfileId = profileId;
        renderResultsSection();
        renderVerdictSummary();
    }

    function toggleCandidateDetail(profileId) {
        state.expanded.has(profileId) ? state.expanded.delete(profileId) : state.expanded.add(profileId);
        state.selectedProfileId = profileId;
        renderResultsSection();
    }

    function renderVerdictSummary() {
        const values = Object.values(state.verdicts);
        const yesCount = values.filter(value => value === 'yes').length;
        const noCount = values.length - yesCount;
        byId('verdict-summary').textContent = values.length === 0
            ? 'Or mark candidates above'
            : `${yesCount} marked good, ${noCount} marked no`;
    }

    function renderQuickFeedback() {
        if (isFrozen()) {
            byId('quick-feedback').replaceChildren();
            return;
        }
        replaceChildren(byId('quick-feedback'), QUICK_FEEDBACK.map(phrase => h('button', {
            type: 'button',
            class: 'quick',
            onclick: () => {
                const box = byId('feedback');
                box.value = box.value ? `${box.value.trim()} ${phrase}` : phrase;
                box.focus();
            },
        }, phrase)));
    }

    function renderEmptyDiagnostics() {
        byId('empty-panel').hidden = false;
        const diagnostics = state.server.filter_diagnostics || [];
        const worstCount = diagnostics.length ? Math.min(...diagnostics.map(entry => entry.match_count)) : 0;

        replaceChildren(byId('empty-diagnostics'), diagnostics.map(entry => h('li', {
                class: entry.match_count === worstCount ? 'is-culprit' : '',
            },
            h('span', {class: 'diagnostics__name'},
                entry.dimension.replace(/_/g, ' '), ' ', h('span', {text: entry.description})),
            h('span', {class: 'diagnostics__count', text: `${entry.match_count} profiles`}))));
    }

    /* ------------------------------ filters panel ------------------------------- */

    function renderFiltersPanel() {
        const filters = state.draft.filters;
        const frozen = isFrozen();

        const isManual = state.server.filters_origin === 'MANUALLY_EDITED';
        const originLabel = isManual ? 'Edited by you' : 'AI generated';
        const originClass = `tag ${isManual ? 'tag--manual' : ''}`;
        byId('filters-origin').textContent = originLabel;
        byId('filters-origin').className = originClass;
        byId('rubric-origin').textContent = originLabel;
        byId('rubric-origin').className = originClass;

        setYearInputs('min-years', 'max-years', filters, frozen);
        renderYearPresets('year-presets', filters, frozen, () => {
            renderFiltersPanel();
            refreshApplyBar();
        });

        renderEditableChips('skills-chips', filters.skills, frozen, value => {
            filters.skills = filters.skills.filter(skill => skill !== value);
            renderFiltersPanel();
            refreshApplyBar();
        });
        renderEditableChips('locations-chips', filters.locations, frozen, value => {
            filters.locations = filters.locations.filter(location => location !== value);
            renderFiltersPanel();
            refreshApplyBar();
        });

        byId('skills-input').hidden = frozen;
        byId('locations-input').hidden = frozen;

        setCheckbox('skills-all', filters.skills_match_all, frozen);
        setCheckbox('remote-ok', filters.remote_ok, frozen);

        renderCompanyTypeToggles('company-types', filters, 'company_types', frozen);
        renderCompanyTypeToggles('past-company-types', filters, 'past_company_types', frozen);
        renderCompanyTypeToggles('exclude-company-types', filters, 'exclude_company_types', frozen);

        refreshApplyBar();
    }

    function setYearInputs(minId, maxId, filters, frozen) {
        const minimum = byId(minId);
        const maximum = byId(maxId);
        minimum.value = filters.min_years_experience ?? '';
        maximum.value = filters.max_years_experience ?? '';
        minimum.disabled = frozen;
        maximum.disabled = frozen;
    }

    function setCheckbox(elementId, isChecked, frozen) {
        const checkbox = byId(elementId);
        checkbox.checked = Boolean(isChecked);
        checkbox.disabled = frozen;
    }

    function renderYearPresets(containerId, filters, frozen, afterChange) {
        replaceChildren(byId(containerId), YEAR_PRESETS.map(preset => {
            const isActive = filters.min_years_experience === preset.min
                && filters.max_years_experience === preset.max;
            return h('button', {
                type: 'button',
                class: 'preset',
                'aria-pressed': String(isActive),
                disabled: frozen,
                onclick: () => {
                    filters.min_years_experience = isActive ? null : preset.min;
                    filters.max_years_experience = isActive ? null : preset.max;
                    afterChange();
                },
            }, preset.label);
        }));
    }

    function renderEditableChips(containerId, values, frozen, onRemove) {
        replaceChildren(byId(containerId), values.map(value => h('span', {class: 'chip-editable'},
            h('span', {text: value}),
            frozen ? null : h('button', {
                type: 'button',
                class: 'chip-remove',
                'aria-label': `Remove ${value}`,
                onclick: () => onRemove(value),
            }, '×'))));
    }

    function renderCompanyTypeToggles(containerId, filters, filterField, frozen) {
        const selected = new Set(filters[filterField]);
        replaceChildren(byId(containerId), COMPANY_TYPES.map(companyType => h('button', {
            type: 'button',
            class: 'toggle',
            'aria-pressed': String(selected.has(companyType)),
            disabled: frozen,
            onclick: () => {
                const current = filters[filterField];
                filters[filterField] = current.includes(companyType)
                    ? current.filter(value => value !== companyType)
                    : [...current, companyType];
                renderFiltersPanel();
                refreshApplyBar();
            },
        }, companyType)));
    }

    function addChipValue(target, filterField, inputElement, afterChange) {
        const value = inputElement.value.trim();
        if (!value) return;
        const current = target[filterField];
        if (!current.some(existing => existing.toLowerCase() === value.toLowerCase())) {
            current.push(value);
        }
        inputElement.value = '';
        afterChange();
    }

    function refreshApplyBar() {
        const isDirty = hasUnappliedEdits();
        byId('filters-actions').hidden = isFrozen() || !isDirty;
        if (isDirty) {
            byId('apply-summary').textContent = describeUnappliedEdits();
        }
    }

    function describeUnappliedEdits() {
        const filtersChanged = JSON.stringify(state.draft.filters) !== JSON.stringify(state.server.filters);
        const rubricChanged = JSON.stringify(state.draft.rubric) !== JSON.stringify(state.server.rubric);
        if (filtersChanged && rubricChanged) return 'Filters and rubric edited';
        return filtersChanged ? 'Filters edited' : 'Rubric edited';
    }

    /* ------------------------------- rubric panel ------------------------------- */

    function renderRubricPanel() {
        const rubric = state.draft.rubric;
        const frozen = isFrozen();

        const summary = byId('rubric-summary');
        summary.value = rubric.summary || '';
        summary.disabled = frozen;

        byId('add-criterion').hidden = frozen;

        replaceChildren(byId('rubric-criteria'), rubric.criteria.map((criterion, index) =>
            renderRubricCriterion(criterion, index, frozen)));
    }

    function renderRubricCriterion(criterion, index, frozen) {
        const weightLabel = h('span', {class: 'criterion__weight', text: formatWeight(criterion.weight)});

        return h('div', {class: 'criterion'},
            h('div', {class: 'criterion__head'},
                h('input', {
                    type: 'text',
                    class: 'criterion__name',
                    value: criterion.name,
                    disabled: frozen,
                    'aria-label': `Criterion ${index + 1} name`,
                    oninput: (event) => {
                        criterion.name = event.target.value;
                        refreshApplyBar();
                    },
                }),
                weightLabel,
                frozen ? null : h('button', {
                    type: 'button',
                    class: 'chip-remove',
                    'aria-label': `Remove criterion ${criterion.name}`,
                    onclick: () => {
                        state.draft.rubric.criteria.splice(index, 1);
                        renderRubricPanel();
                        refreshApplyBar();
                    },
                }, '×')),
            h('textarea', {
                class: 'criterion__description',
                rows: 2,
                disabled: frozen,
                'aria-label': `Criterion ${index + 1} description`,
                oninput: (event) => {
                    criterion.description = event.target.value;
                    refreshApplyBar();
                },
            }, criterion.description || ''),
            h('input', {
                type: 'range',
                min: '0',
                max: '100',
                step: '5',
                value: String(Math.round(criterion.weight * 100)),
                disabled: frozen,
                'aria-label': `Weight for ${criterion.name}`,
                oninput: (event) => {
                    criterion.weight = Number(event.target.value) / 100;
                    weightLabel.textContent = formatWeight(criterion.weight);
                    refreshApplyBar();
                },
            }));
    }

    function formatWeight(weight) {
        return `${Math.round(weight * 100)}%`;
    }

    function addRubricCriterion() {
        state.draft.rubric.criteria.push({
            name: 'New criterion',
            description: 'What does excellent look like here?',
            weight: 0.2,
        });
        renderRubricPanel();
        refreshApplyBar();
        const inputs = byId('rubric-criteria').querySelectorAll('.criterion__name');
        const lastInput = inputs[inputs.length - 1];
        if (lastInput) {
            lastInput.focus();
            lastInput.select();
        }
    }

    /* ------------------------------ history panel ------------------------------- */

    function renderHistoryPanel() {
        replaceChildren(byId('history'), state.server.history.slice().reverse().map(round => {
            const isManual = round.source === 'manual_edit';
            return h('div', {class: `round ${isManual ? 'round--manual' : ''}`},
                h('div', {class: 'round__head'},
                    h('span', {
                        class: 'round__label',
                        text: isManual ? `Round ${round.round_number} · your edit` : `Round ${round.round_number}`,
                    }),
                    h('span', {class: 'round__time', text: round.at})),
                round.feedback ? h('p', {class: 'round__said', text: `"${round.feedback}"`}) : null,
                round.reply ? h('p', {class: 'round__reply', text: round.reply}) : null,
                round.changes.length
                    ? h('div', {class: 'changes'}, round.changes.map(renderChangeRow))
                    : h('p', {class: 'change__reason', text: 'No change to the search definition.'}));
        }));
    }

    function renderChangeRow(change) {
        return h('div', {class: 'change'},
            h('p', {class: 'change__field', text: `${change.type}: ${String(change.field).replace(/_/g, ' ')}`}),
            h('p', {class: 'change__diff'},
                h('span', {class: 'change__before', text: emptyValueLabel(change.before)}),
                h('span', {class: 'change__arrow', text: '→'}),
                h('span', {class: 'change__after', text: emptyValueLabel(change.after)})),
            change.reason ? h('p', {class: 'change__reason', text: change.reason}) : null);
    }

    /** Models write "nothing" a dozen ways; they should all render as the same quiet "none". */
    const EMPTY_VALUE_WORDS = new Set(['', 'null', 'none', 'n/a', 'undefined', '[]', 'empty']);

    function emptyValueLabel(value) {
        if (value === null || value === undefined) return 'none';
        return EMPTY_VALUE_WORDS.has(String(value).trim().toLowerCase()) ? 'none' : value;
    }

    /* ------------------------------ frozen summary ------------------------------ */

    function renderFrozenSummary() {
        const panel = byId('frozen-summary');
        if (!isFrozen()) {
            panel.hidden = true;
            return;
        }
        const data = state.server;
        panel.hidden = false;

        const shortlist = data.results.slice(0, TOP_CANDIDATES_SHOWN);
        byId('frozen-stats').textContent =
            `${data.matched_count} candidates matched, top ${shortlist.length} shortlisted. `
            + `Search refined ${data.history.length} ${data.history.length === 1 ? 'time' : 'times'}.`;

        replaceChildren(byId('frozen-shortlist'), shortlist.map((candidate, index) => h('li', {
            style: `animation-delay:${index * 50}ms`,
        },
            h('span', {class: 'shortlist__rank', text: `${candidate.rank}.`}),
            h('span', {class: 'shortlist__who'},
                h('p', {class: 'shortlist__name', text: candidate.name}),
                h('p', {
                    class: 'shortlist__detail',
                    text: `${candidate.current_title} · ${candidate.years_experience} yrs · ${candidate.current_company} (${candidate.current_company_type}) · ${candidate.location}`,
                })),
            h('span', {class: 'shortlist__score', text: candidate.score}))));

        replaceChildren(byId('frozen-filters'), describeFiltersAsDefinitionList(data.filters));

        replaceChildren(byId('frozen-rubric'),
            h('p', {text: data.rubric.summary}),
            h('ul', {}, data.rubric.criteria.map(criterion => h('li', {},
                h('b', {text: formatWeight(criterion.weight)}),
                h('span', {text: `${criterion.name} — ${criterion.description}`})))));

        replaceChildren(byId('frozen-history'), data.history.map(round => h('li', {},
            h('b', {text: `Round ${round.round_number}: `}),
            round.reply || round.feedback || 'Search updated.')));
    }

    function describeFiltersAsDefinitionList(filters) {
        const rows = [
            ['Experience', describeYearRange(filters)],
            ['Skills', filters.skills.join(', ') + (filters.skills_match_all ? ' (all required)' : '')],
            ['Locations', filters.locations.join(', ') + (filters.remote_ok ? ' (remote ok)' : '')],
            ['Currently at', filters.company_types.join(', ')],
            ['Has worked at', filters.past_company_types.join(', ')],
            ['Excluding', filters.exclude_company_types.join(', ')],
        ];
        return rows
            .filter(([, value]) => value && value.trim() && !value.trim().startsWith('('))
            .map(([label, value]) => [h('dt', {text: label}), h('dd', {text: value})])
            .flat();
    }

    function describeYearRange(filters) {
        const minimum = filters.min_years_experience;
        const maximum = filters.max_years_experience;
        if (minimum != null && maximum != null) return `${minimum}–${maximum} years`;
        if (minimum != null) return `${minimum}+ years`;
        if (maximum != null) return `up to ${maximum} years`;
        return '';
    }

    /* -------------------------------- exporting --------------------------------- */

    function buildShortlistRows() {
        return state.server.results.slice(0, TOP_CANDIDATES_SHOWN).map(candidate => [
            candidate.rank, candidate.name, candidate.current_title, candidate.years_experience,
            candidate.location, candidate.current_company, candidate.current_company_type,
            candidate.score, candidate.explanation,
        ]);
    }

    function copyShortlistToClipboard() {
        const text = buildShortlistRows()
            .map(row => `${row[0]}. ${row[1]} — ${row[2]}, ${row[3]} yrs, ${row[5]} (${row[6]}), ${row[4]} — fit ${row[7]}`)
            .join('\n');
        navigator.clipboard.writeText(text)
            .then(() => showToast('Shortlist copied to the clipboard'))
            .catch(() => showToast('Could not reach the clipboard'));
    }

    function downloadShortlistAsCsv() {
        const header = ['rank', 'name', 'title', 'years', 'location', 'company', 'company_type', 'fit_score', 'why'];
        const csv = [header, ...buildShortlistRows()]
            .map(row => row.map(cell => `"${String(cell).replace(/"/g, '""')}"`).join(','))
            .join('\n');
        const url = URL.createObjectURL(new Blob([csv], {type: 'text/csv;charset=utf-8'}));
        const link = h('a', {href: url, download: 'flexiple-shortlist.csv'});
        document.body.append(link);
        link.click();
        link.remove();
        URL.revokeObjectURL(url);
        showToast('Shortlist downloaded');
    }

    /* ------------------------------ landing screen ------------------------------- */

    function setLandingStatus(message, isWarning) {
        const status = byId('landing-status');
        status.textContent = message;
        status.classList.toggle('is-warning', Boolean(isWarning));
    }

    function switchLandingTab(activeTabId) {
        const tabs = [['tab-describe', 'panel-describe'], ['tab-build', 'panel-build']];
        tabs.forEach(([tabId, panelId]) => {
            const isActive = tabId === activeTabId;
            byId(tabId).classList.toggle('is-active', isActive);
            byId(tabId).setAttribute('aria-selected', String(isActive));
            byId(panelId).hidden = !isActive;
        });
        byId(activeTabId === 'tab-build' ? 'build-skills' : 'query').focus();
        if (activeTabId === 'tab-build') {
            refreshManualBuilder();
        }
    }

    function refreshManualBuilder() {
        const frozen = false;
        renderEditableChips('build-skills-chips', state.builder.skills, frozen, value => {
            state.builder.skills = state.builder.skills.filter(skill => skill !== value);
            refreshManualBuilder();
        });
        renderEditableChips('build-locations-chips', state.builder.locations, frozen, value => {
            state.builder.locations = state.builder.locations.filter(location => location !== value);
            refreshManualBuilder();
        });
        setYearInputs('build-min-years', 'build-max-years', state.builder, frozen);
        renderYearPresets('build-year-presets', state.builder, frozen, refreshManualBuilder);
        renderCompanyTypeToggles('build-company-types', state.builder, 'company_types', frozen);
        renderCompanyTypeToggles('build-past-company-types', state.builder, 'past_company_types', frozen);
        byId('build-skills-all').checked = state.builder.skills_match_all;
        byId('build-remote-ok').checked = state.builder.remote_ok;
        previewManualFilterCount();
    }

    const previewManualFilterCount = debounce(async () => {
        try {
            const preview = await callApi('/api/search/preview', {filters: state.builder});
            const count = byId('build-count');
            count.replaceChildren(
                h('strong', {text: preview.matched_count}),
                ` of ${preview.talent_pool_size} profiles match these criteria`);
            byId('build-submit').disabled = preview.matched_count === 0 || state.busy;
            if (preview.matched_count === 0) {
                count.append(' — try loosening one of them');
            }
        } catch (failure) {
            byId('build-count').textContent = '';
        }
    }, PREVIEW_DEBOUNCE_MS);

    async function loadEnvironmentStatus() {
        try {
            const status = await (await fetch('/api/status')).json();
            setLandingStatus(status.llm_configured
                ? `${status.talent_pool_size} profiles loaded · reasoning with ${status.model}`
                : 'No AI key configured — set LLM_API_KEY and restart to use the AI. Building a search by hand still works.',
                !status.llm_configured);
        } catch (failure) {
            setLandingStatus('Could not reach the server.', true);
        }
    }

    async function loadTalentPoolVocabulary() {
        try {
            state.vocabulary = await (await fetch('/api/vocabulary')).json();
            fillDatalist('skill-options', state.vocabulary.skills);
            fillDatalist('location-options', state.vocabulary.locations);
        } catch (failure) {
            // Autocomplete is a convenience; typing still works without it.
        }
    }

    function fillDatalist(datalistId, values) {
        replaceChildren(byId(datalistId), (values || []).map(value => h('option', {value})));
    }

    /* ------------------------------ keyboard control ----------------------------- */

    function isTypingInAField(target) {
        return ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName) || target.isContentEditable;
    }

    function handleGlobalKeyboard(event) {
        if (byId('workspace').hidden) return;

        if (event.key === 'Escape' && isViewFiltered()) {
            clearViewFilters();
            return;
        }
        if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'z' && !isTypingInAField(event.target)) {
            event.preventDefault();
            undoLastRefinement();
            return;
        }
        if (isTypingInAField(event.target)) return;

        const visible = state.server ? candidatesAfterViewFilters() : [];
        switch (event.key) {
            case '/':
                event.preventDefault();
                byId('result-search').focus();
                break;
            case '?':
                event.preventDefault();
                byId('shortcuts-dialog').showModal();
                break;
            case 'j':
                moveSelection(visible, 1);
                break;
            case 'k':
                moveSelection(visible, -1);
                break;
            case 'y':
                applyVerdictToSelection('yes');
                break;
            case 'n':
                applyVerdictToSelection('no');
                break;
            case 'e':
                if (state.selectedProfileId) toggleCandidateDetail(state.selectedProfileId);
                break;
            case 'c':
                event.preventDefault();
                byId('feedback').focus();
                break;
            default:
                break;
        }
    }

    function moveSelection(visible, step) {
        if (!visible.length) return;
        const shown = state.showAllCandidates ? visible : visible.slice(0, TOP_CANDIDATES_SHOWN);
        const currentIndex = shown.findIndex(candidate => candidate.profile_id === state.selectedProfileId);
        const nextIndex = Math.min(Math.max(currentIndex + step, 0), shown.length - 1);
        state.selectedProfileId = shown[currentIndex === -1 ? 0 : nextIndex].profile_id;
        renderResultsSection();
        const card = document.querySelector(`.card[data-profile-id="${state.selectedProfileId}"]`);
        if (card) card.scrollIntoView({behavior: 'smooth', block: 'nearest'});
    }

    function applyVerdictToSelection(verdictValue) {
        if (!state.selectedProfileId || isFrozen()) return;
        toggleVerdict(state.selectedProfileId, verdictValue);
    }

    /* --------------------------------- wiring ----------------------------------- */

    function attachLandingHandlers() {
        byId('tab-describe').addEventListener('click', () => switchLandingTab('tab-describe'));
        byId('tab-build').addEventListener('click', () => switchLandingTab('tab-build'));

        byId('search-form').addEventListener('submit', startSearchFromFreeText);
        byId('query').addEventListener('keydown', (event) => {
            if (event.key === 'Enter' && !event.shiftKey) {
                event.preventDefault();
                byId('search-form').requestSubmit();
            }
        });
        document.querySelectorAll('.example').forEach(button => button.addEventListener('click', () => {
            byId('query').value = button.dataset.query;
            byId('query').focus();
        }));

        bindChipInput('build-skills', state.builder, 'skills', refreshManualBuilder);
        bindChipInput('build-locations', state.builder, 'locations', refreshManualBuilder);
        bindYearInput('build-min-years', state.builder, 'min_years_experience', refreshManualBuilder);
        bindYearInput('build-max-years', state.builder, 'max_years_experience', refreshManualBuilder);
        byId('build-skills-all').addEventListener('change', (event) => {
            state.builder.skills_match_all = event.target.checked;
            previewManualFilterCount();
        });
        byId('build-remote-ok').addEventListener('change', (event) => {
            state.builder.remote_ok = event.target.checked;
            previewManualFilterCount();
        });
        byId('build-reset').addEventListener('click', () => {
            state.builder = newEmptyFilters();
            refreshManualBuilder();
        });
        byId('build-submit').addEventListener('click', startSearchFromManualFilters);
    }

    function attachWorkspaceHandlers() {
        byId('feedback-form').addEventListener('submit', refineFromFeedback);
        byId('feedback').addEventListener('keydown', (event) => {
            if (event.key === 'Enter' && (event.metaKey || event.ctrlKey)) {
                event.preventDefault();
                byId('feedback-form').requestSubmit();
            }
        });

        bindChipInput('skills-input', null, 'skills', () => { renderFiltersPanel(); refreshApplyBar(); });
        bindChipInput('locations-input', null, 'locations', () => { renderFiltersPanel(); refreshApplyBar(); });
        bindYearInput('min-years', null, 'min_years_experience', refreshApplyBar);
        bindYearInput('max-years', null, 'max_years_experience', refreshApplyBar);

        byId('skills-all').addEventListener('change', (event) => {
            state.draft.filters.skills_match_all = event.target.checked;
            refreshApplyBar();
        });
        byId('remote-ok').addEventListener('change', (event) => {
            state.draft.filters.remote_ok = event.target.checked;
            refreshApplyBar();
        });
        byId('rubric-summary').addEventListener('input', (event) => {
            state.draft.rubric.summary = event.target.value;
            refreshApplyBar();
        });
        byId('add-criterion').addEventListener('click', addRubricCriterion);

        byId('apply-button').addEventListener('click', applySearchDefinitionEdits);
        byId('reset-button').addEventListener('click', () => {
            adoptServerState(state.server);
            renderWorkspace();
            showToast('Edits discarded');
        });

        byId('result-search').addEventListener('input', debounce((event) => {
            state.view.text = event.target.value;
            state.showAllCandidates = false;
            renderResultsSection();
        }, 120));
        byId('sort-select').addEventListener('change', (event) => {
            state.view.sort = event.target.value;
            renderResultsSection();
        });
        byId('marked-only').addEventListener('click', () => {
            state.view.markedOnly = !state.view.markedOnly;
            renderResultsSection();
        });
        byId('clear-view').addEventListener('click', clearViewFilters);
        byId('clear-view-empty').addEventListener('click', clearViewFilters);

        byId('rank-ai').addEventListener('click', () => runWithBusyGuard(() => rankCurrentSearchDefinition('ai')));
        byId('rank-local').addEventListener('click', () => runWithBusyGuard(() => rankCurrentSearchDefinition('local')));

        byId('show-all-button').addEventListener('click', () => {
            state.showAllCandidates = !state.showAllCandidates;
            renderResultsSection();
        });

        byId('undo-button').addEventListener('click', undoLastRefinement);
        byId('freeze-button').addEventListener('click', () => byId('freeze-dialog').showModal());
        byId('freeze-cancel').addEventListener('click', () => byId('freeze-dialog').close());
        byId('freeze-confirm').addEventListener('click', () => {
            byId('freeze-dialog').close();
            freezeSearch();
        });
        byId('restart-button').addEventListener('click', () => window.location.reload());

        byId('shortcuts-button').addEventListener('click', () => byId('shortcuts-dialog').showModal());
        byId('shortcuts-close').addEventListener('click', () => byId('shortcuts-dialog').close());

        byId('copy-shortlist').addEventListener('click', copyShortlistToClipboard);
        byId('download-shortlist').addEventListener('click', downloadShortlistAsCsv);

        document.addEventListener('keydown', handleGlobalKeyboard);
    }

    /** target === null means "the live draft", which only exists once a session has started. */
    function bindChipInput(inputId, target, filterField, afterChange) {
        const input = byId(inputId);
        const resolveTarget = () => target || state.draft.filters;
        input.addEventListener('keydown', (event) => {
            if (event.key !== 'Enter') return;
            event.preventDefault();
            addChipValue(resolveTarget(), filterField, event.target, afterChange);
        });
        input.addEventListener('blur', (event) => addChipValue(resolveTarget(), filterField, event.target, afterChange));
    }

    function bindYearInput(inputId, target, filterField, afterChange) {
        byId(inputId).addEventListener('input', (event) => {
            const resolved = target || state.draft.filters;
            resolved[filterField] = event.target.value === '' ? null : Number(event.target.value);
            afterChange();
        });
    }

    attachLandingHandlers();
    attachWorkspaceHandlers();
    loadEnvironmentStatus();
    loadTalentPoolVocabulary();
    byId('query').focus();
})();
