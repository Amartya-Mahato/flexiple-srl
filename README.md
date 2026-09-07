# Flexiple Sourcing — the refinement loop

A small, complete slice of an AI recruiter, with **two ways in and no dead ends**.

Describe who you are looking for in plain English and the app turns it into **objective filters** and
a **subjective fit rubric** — or skip the AI entirely and build those filters by hand, with live match
counts as you go. Either way the filters are applied to the talent pool in code, the survivors are
ranked and explained, and you keep adjusting: in chat, with per-candidate yes/no, by editing filters
and rubric directly, or by slicing the results instantly with search, sort and facets. When you are
happy, you freeze the search and get a final shortlist.

One Spring Boot process serves both the API and the UI. There is no second server, no Node, no
frontend build step.

```
free text ──► LLM: filters + rubric  (operation 1) ─┐
                                                    ├─► local filtering in Java (deterministic)
hand-built filters ─────────────────────────────────┘        │
                                                             ▼
                                    ┌─── Quick rank: scored in Java, instant, no key needed
                                    └─── Rank with AI: LLM scores + cites evidence (operation 2)
                                                             │
                                        grounding + validation in Java
                                        (every cited fact checked against the real profile)
                                                             │
                                                    recruiter reacts
                                                             │
                          ┌──────────────────────────────────┼───────────────────────────────┐
                          ▼                                  ▼                               ▼
              chat + yes/no verdicts              edit filters / rubric            search, sort, facets
              → LLM refines (operation 3)         → re-runs the search             → instant, view only
                          └──────────────────────────────────┴───────────────────────────────┘
                                                             │
                                                    repeat, undo → freeze
```

---

## 1. What you need

**Just a JDK 25.** Maven is *not* required — the Maven Wrapper (`mvnw` / `mvnw.cmd`) is committed and
downloads Maven itself on the first run.

Check what you have:

```bash
java -version      # must print 25.x
```

If that fails or prints an older version, install a JDK 25:

| Platform | Command |
|---|---|
| Windows | `winget install Microsoft.OpenJDK.25` (alternative: `winget install EclipseAdoptium.Temurin.25.JDK`) |
| macOS | `brew install openjdk@25`, then run the `sudo ln -sfn ...` line brew prints at the end |
| Linux / anything | `curl -s "https://get.sdkman.io" \| bash`, restart the shell, then `sdk install java 25-tem` |

Then **open a new terminal** (installers only update the PATH for new shells) and check `java -version`
again.

<details>
<summary>If <code>java -version</code> still shows an old version</summary>

Another JDK is earlier on your PATH. Point `JAVA_HOME` at the new one for this shell:

```powershell
# Windows PowerShell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-25.0.4"   # your actual install path
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
```

```bash
# macOS / Linux
export JAVA_HOME=$(/usr/libexec/java_home -v 25)     # macOS
export PATH="$JAVA_HOME/bin:$PATH"
```
</details>

You also need an internet connection the first time you run it (to download Maven and the
dependencies) and for every AI call.

## 2. Get an API key

The app talks to **Google Gemini**. A free key takes about thirty seconds:

1. Go to <https://aistudio.google.com/apikey>
2. Sign in and click **Create API key**
3. Copy it — that string is the value of `LLM_API_KEY` below

The key is read from the environment, is only ever used server-side, and is never logged, never
returned by any endpoint, and never sent to the browser. Do not commit it; `.gitignore` already
covers `.env` files.

## 3. Run it — two commands

**Windows (PowerShell)**

```powershell
$env:LLM_API_KEY = "paste-your-key-here"
.\mvnw.cmd spring-boot:run
```

**Windows (cmd.exe)**

```bat
set LLM_API_KEY=paste-your-key-here
mvnw.cmd spring-boot:run
```

**macOS / Linux**

```bash
export LLM_API_KEY="paste-your-key-here"
./mvnw spring-boot:run
```

Then open <http://localhost:8080>.

The first run downloads Maven and the dependencies and takes a few minutes; after that the app starts
in about two seconds. You will know it is up when the log says:

```
Loaded 48 profiles from .../profiles.json
Tomcat started on port 8080 (http)
```

Stop it with `Ctrl+C`.

## 4. Try this first

Paste this into the search box and press Enter:

```
RDS developers with 4-7 years of experience who have worked at startups, for a role based in Bangalore.
```

Then, on the results, try:

```
2 is too junior for this role. 1 and 3 are exactly right. I care much more about production
database ownership than about breadth of languages.
```

You will see exactly what changed in the filters and the rubric, and why, before the new results
appear. Then hit **Freeze search**.

## 5. Two ways to search

The landing screen has two tabs, and both are first-class.

**Describe it** — the AI path. Free text goes to the model, which writes the filters and the rubric;
candidates are then scored against that rubric and every claim is checked against the profile.

**Build it by hand** — the manual path. No model call at all. Add skills and locations with
autocomplete drawn from the real dataset, set an experience range (or tap a preset), pick company
backgrounds, and watch the match count update live as you go. Hit search and you get a ranked list
instantly. **This works with no API key at all.**

The two are not separate products. A hand-built search lands in the same workspace with the same
chat, the same editable rubric and the same freeze; an AI search can be re-ranked instantly at any
time. The **Quick rank / Rank with AI** switch above the results says which produced the current
order, and swaps between them in one click:

| | Quick rank | Rank with AI |
|---|---|---|
| Speed | instant | a few seconds |
| Needs a key | no | yes |
| What the score means | the share of *your criteria* this person meets | judged against your *fit rubric* |
| Explanation | assembled from profile fields | written by the model, then fact-checked |

Once results are on screen there is a third layer, and it never touches the search itself: the
toolbar's text search, sort and location/background facets filter only **what you are looking at**.
They are instant, they are not recorded in the history, and `Esc` clears them.

### Keyboard

`/` filter the list · `j`/`k` move · `y`/`n` mark good or not · `e` expand a profile ·
`c` jump to feedback · `Ctrl+Enter` refine · `Ctrl+Z` undo · `Esc` clear view filters · `?` the full list.

## 6. Configuration

Everything has a working default except the key.

| Variable | Default | What it does |
|---|---|---|
| `LLM_API_KEY` | *(none)* | Your Gemini API key. Required for the AI path. Without it the app still starts, and the **Build it by hand** path works completely — only AI calls return a clear `LLM_NOT_CONFIGURED` message. |
| `LLM_MODEL` | `gemini-3.6-flash` | The model to use. Change this if your key does not have access to the default. |
| `LLM_BASE_URL` | `https://generativelanguage.googleapis.com/v1beta` | Provider endpoint. |
| `LLM_TIMEOUT_SECONDS` | `60` | Read timeout for one AI call. |
| `PROFILES_FILE` | `./profiles.json` | Path to the talent pool. Resolved relative to where you start the app. |
| `SERVER_PORT` | `8080` | Change if 8080 is already taken on your machine. |

Example — different port and model:

```powershell
$env:LLM_API_KEY = "..."; $env:SERVER_PORT = "8099"; $env:LLM_MODEL = "gemini-2.5-flash"
.\mvnw.cmd spring-boot:run
```

## 7. Tests

```powershell
.\mvnw.cmd test          # Windows
./mvnw test              # macOS / Linux
```

63 tests, all offline — no API key needed, no network calls. They cover profile loading, skill
normalisation, the filter engine (including the canonical search returning exactly p01–p06), local
relevance ranking, validation of malformed model output, grounding, the refinement loop, undo,
freeze, the manual search path proving it makes no model calls, and the HTTP error contract.

## 8. Troubleshooting

| Symptom | Fix |
|---|---|
| `Web server failed to start. Port 8080 was already in use.` | `SERVER_PORT=8099` (see above), then open <http://localhost:8099>. |
| Banner: *No AI key configured* | `LLM_API_KEY` was not set in the shell you launched from. Set it and restart — env vars are read at startup. |
| *The AI provider rejected the API key* | The key is wrong, or the Generative Language API is not enabled for that project. Create a fresh key at <https://aistudio.google.com/apikey>. |
| *The AI model ... was not found* | Your key cannot use `gemini-3.6-flash`. Set `LLM_MODEL` to one it can, e.g. `gemini-2.5-flash`. |
| *Rate limited by the AI service* | Free-tier quota. Wait a few seconds and press **Try again** — your search state is untouched. |
| `Cannot read the talent pool at ...` | You started the app from somewhere other than the project directory. `cd` into it, or set `PROFILES_FILE` to the absolute path of `profiles.json`. |
| First build hangs or fails to download | Corporate proxy or no internet. Maven needs to reach `repo.maven.apache.org` once. |

## 9. Showing the failure and recovery path

There is a documented, off-by-default switch that makes the **next** AI call fail in a chosen way, so
the recovery experience can be demonstrated without waiting for a real outage. Real calls stay real —
this only breaks one response.

```powershell
# PowerShell
Invoke-RestMethod -Uri http://localhost:8080/api/dev/fail-next -Method Post `
  -ContentType 'application/json' -Body '{"mode":"malformed"}'
```

```bash
# curl
curl -X POST http://localhost:8080/api/dev/fail-next \
     -H 'Content-Type: application/json' -d '{"mode":"malformed"}'
```

Modes: `malformed` (the model returns unparseable JSON — the real parser and validators reject it),
`timeout`, `rate_limit`, `empty`. Then refine or re-run in the UI: you get a friendly, specific error
with a **Try again** button, the previous results stay on screen, and the search definition is
completely unchanged. `POST /api/dev/fail-next/clear` disarms it.

---

## Architecture

### One process, two halves

```
src/main/resources/static/     index.html, styles.css, app.js   - served by Spring Boot itself
src/main/resources/prompts/    the four prompts, as plain text
src/main/java/com/flexiple/sourcing/
  SourcingService              the loop: parse -> filter -> score -> refine -> freeze
  profiles/                    ProfileRepository, SkillNormalizer, FilterEngine
  llm/                         GeminiClient, GeminiSchemas, PromptLoader, StructuredResponseReader
  llm/ops/                     the three AI operations, one class each
  validation/                  SearchDefinitionValidator, CandidateScoreGrounder
  session/                     SessionStore (in memory)
  web/                         controllers, DTOs, the single error shape
```

The frontend is plain HTML, CSS and JavaScript with no framework and no build step, served from
`src/main/resources/static`. It calls the API same-origin (`fetch('/api/...')`), so there is no CORS
configuration to get wrong.

### The API

| Endpoint | What it does |
|---|---|
| `POST /api/search/parse` | Free text in; session id, filters, rubric and the match count out. AI operation 1. |
| `POST /api/search/manual-start` | Hand-built filters in; a live session with locally ranked results out. **No model call.** |
| `POST /api/search/preview` | How many profiles a set of filters would select, plus per-dimension counts. No session, no model, no side effects — this is what makes the builder's live count possible. |
| `POST /api/search/run` | Ranks whoever passed the filters. `scoring_mode: "ai"` is AI operation 2; `"local"` ranks in Java. |
| `POST /api/search/refine` | Chat feedback plus per-candidate verdicts in; new definition, explained changes and new results out. AI operation 3. |
| `POST /api/search/manual-update` | The recruiter's own edits to filters or rubric. Re-runs the search; no AI call for the definition. |
| `POST /api/search/undo` | Steps back one round, restoring the definition that round started from. |
| `POST /api/search/freeze` | Makes the search final. |
| `POST /api/search/state` | Re-reads current state, used by the UI to resynchronise after a failure. |
| `GET /api/status` | Whether a key is configured, which model, how many profiles. Never the key itself. |
| `GET /api/vocabulary` | The skills, locations, titles and companies that actually exist in the pool, so the manual controls autocomplete against real values. |
| `POST /api/dev/fail-next` | The demo fault switch described above. |

Parsing and scoring are **separate calls on purpose**: the filters and rubric paint as soon as they
exist, and the slower ranking step runs behind its own progress state. That way the staged loading
copy ("Understanding your search" → "Filtering 48 profiles" → "Ranking candidates") describes work
that is genuinely happening rather than being a timer.

### Session state

One in-memory `SearchSession` per search, held in a `ConcurrentHashMap`, keyed by an id the client
echoes back. It holds the original query, the current filters and rubric, the current ranked results,
where the definition came from (AI or hand-edited), the full refinement history, and whether it is
frozen. A browser refresh starts a new search. Nothing is persisted — that is deliberate, see below.

**The state is only written after every validation step has passed.** A failed or nonsensical AI
response is a no-op: the previous filters, rubric and results are still exactly as they were, and the
API returns a coded error the UI can recover from.

### Filtering happens in Java, not in the model

The model decides *what matching means*; `FilterEngine` decides *who matches*. Filters are applied as
AND across dimensions and OR within a dimension, over skills, years of experience, location, current
company background, past company background, and exclusions.

`SkillNormalizer` is deliberately small: lowercase, strip punctuation, a short alias table
(`RDS`/`Amazon RDS` → `AWS RDS`, `postgres` → `PostgreSQL`, `k8s` → `Kubernetes`, …) and a containment
rule with a minimum length so `Go` cannot match `MongoDB`. No embeddings, no fuzzy-matching library.

When nothing matches, the empty state does not just say zero: `countMatchesPerDimension` reports how
many profiles each filter would keep **on its own**, so the recruiter can see which single filter is
doing the damage.

### Two rankers, one honest label

`CandidateScorer` asks the model to judge candidates against the rubric. `LocalRelevanceScorer` does
it in Java: the score is *the share of the criteria you actually set that this person satisfies*, with
skills counting partially when only some are present and a location that only passed because remote
was allowed counting less than living in the city.

That means when your filters cannot tell six people apart, all six honestly score 100 — and the UI
says so in the results line rather than manufacturing false precision. The label under each score
reads **match** for the local ranking and **fit** for the AI one. Ties break on experience, then id,
so the same search always produces the same order.

### Grounded explanations

This is the part that decides whether a recruiter trusts the product, so none of it is left to the
prompt alone.

The model returns, per candidate, a score, two to four pieces of **structured evidence**
(`{field, value}`) and a one-sentence summary. `CandidateScoreGrounder` then:

1. **Rejects any candidate id** that is not in the set we filtered locally, and any score outside 0–100.
2. **Checks every piece of evidence against the real profile** — the skill must be one they list, the
   employer must be one they worked at, the years must be their actual years. Evidence that does not
   check out is dropped, not shown.
3. **Throws away a summary that names any employer from the dataset that is not on that profile** — a
   cheap, precise catch for the most damaging kind of hallucination.
4. **Falls back to an explanation assembled purely from profile fields** when nothing survives, so a
   card is never blank and never unsupported.
5. **Adds back any profile the model forgot to score**, at the bottom with a fact-only explanation, so
   nobody who passed the objective filters can silently disappear.

Ranking is then deterministic: score descending, id ascending.

### Failure handling

`GeminiClient` is the only class that talks to the provider. It has an explicit read timeout, retries
**once** for transient failures (timeout, network, 429, 5xx) and never for a bad request or a
validation failure, and translates every outcome into a stable code:

`LLM_NOT_CONFIGURED`, `LLM_TIMEOUT`, `LLM_RATE_LIMITED`, `LLM_UNAVAILABLE`, `INVALID_LLM_RESPONSE`,
plus `SESSION_FROZEN`, `SESSION_NOT_FOUND` and `INVALID_REQUEST` from the session and request layers.

Every error comes back in one shape — `{"code": ..., "message": ..., "retryable": true|false}` — which
the frontend maps to a specific, human banner with a **Try again** button, without ever clearing the
results already on screen. Provider response bodies are never forwarded; they can contain quota and
account detail.

### LLM prompts

All four are plain text in [`src/main/resources/prompts/`](src/main/resources/prompts/), loaded and
cached at first use, with `{{placeholders}}` filled in by `PromptLoader`:

| File | Used by |
|---|---|
| `system-instruction.txt` | Shared by all three operations: JSON only, never invent a fact, never invent an id, keep objective and subjective apart. |
| `parse-search.txt` | Operation 1. Free text → filters + rubric. Shows the model the locations and skill spellings that actually exist in the dataset. |
| `score-profiles.txt` | Operation 2. Rubric + filtered candidates → score, cited evidence, one-sentence summary. |
| `refine-search.txt` | Operation 3. Query + current definition + what was on screen + verdicts + feedback → new definition + explained changes + a reply. |

Edit them freely; no code changes needed.

Output is constrained at generation time too: `GeminiSchemas` builds a JSON schema for each operation
and passes it as Gemini's `responseSchema` with `responseMimeType: application/json`. The two
experience-range fields are **required but nullable** on purpose — when they were merely optional, the
model quietly dropped them and a stated "4–7 years" silently stopped being part of the search.

---

## Decisions

### What I prioritised

- **The whole loop, working, with real AI calls.** Free text → filters and rubric → local filtering →
  scoring → conversational refinement → freeze. A demo that covers four of those five steps well is
  worth less than one that closes.
- **Explanations a recruiter can trust.** Structured evidence, verified field by field against the
  real profile, with an employer-name check and a facts-only fallback. "Don't hallucinate" in a prompt
  is not a control; validation in Java is.
- **Refinement that visibly responds.** Every round records what the filters and rubric were before
  and after, a structured list of changes with a reason for each, and a one-line reply. The recruiter
  sees `min_years_experience: 4 → 5 — candidate #2 was too junior` before the new results, not just
  a different list of people.
- **Failing without losing work.** Session state changes only after validation passes, so a malformed
  or rate-limited response leaves the search exactly as it was. Every state — first load, each loading
  stage, empty results, each failure, frozen — is designed rather than defaulted.
- **Keeping the model out of the decision.** The model defines what matching means; Java decides who
  matches, deterministically, from the file.

- **A manual path that is genuinely equal, not a fallback.** The AI is the fastest way to *start* a
  search, but it is a poor way to make a small correction. Everything the model can set, a person can
  set directly: skills and locations with autocomplete from the real dataset, an experience range with
  presets, all three company-background dimensions including exclusions, and every rubric criterion
  with add, remove and weight. The builder shows the match count live, so the cost of each criterion
  is visible before you commit rather than after.
- **Three layers of control, kept clearly distinct.** Changing the *search definition* is a server
  round trip that changes who is in the set and is recorded in the history. Changing the *view* —
  text search, sort, location and background facets — is instant client-side state that never touches
  the search. Conflating those two is how filter UIs become untrustworthy.
- **Undo.** A refinement can go wrong, and "ask the AI to put it back" is not a recovery mechanism.
  Each round already stores the definition it started from, so stepping back is exact rather than
  approximate.

### What I cut, and why

- **Authentication and multi-user support.** The brief is one recruiter, one search. Login would be
  scaffolding around the part being evaluated.
- **A database and any cross-session persistence.** A `ConcurrentHashMap` is the honest shape of "one
  session, refresh to start again". Adding Postgres would be migrations and wiring in exchange for
  nothing the flow needs.
- **Semantic or vector search.** With 48 profiles, embeddings would be a worse `SkillNormalizer`:
  slower, harder to explain, and impossible to unit test. A twenty-line alias table with a containment
  guard covers `RDS` / `AWS RDS` / `Amazon RDS` and is auditable. At 98M profiles this is the first
  thing that would have to change — and it would change behind the same `FilterEngine` interface.
- **Anything resembling real 98M-person infrastructure** — sharding, an index, a query planner,
  queueing, caching. The dataset is a file; pretending otherwise would be architecture theatre.
- **Docker, Kubernetes, CI.** One command has to work on the reviewer's machine. `./mvnw
  spring-boot:run` does, and a container would add a prerequisite rather than remove one.
- **Streaming the model's tokens to the browser.** Splitting parse from scoring already gives the
  recruiter something real to look at within a couple of seconds, which is what streaming would have
  bought, without the SSE plumbing.
- **A frontend framework and any build tooling.** The UI is a landing screen and a workspace. Vanilla
  JS with a twelve-line DOM helper is smaller than the config a bundler would need, and it keeps the
  promise of one command and one process.

### Judgement calls worth flagging

- **The local score is allowed to be boring.** It would have been easy to add tie-breaking heuristics
  so the list never shows repeated numbers, but every one of them would have been invented signal.
  A column of 100s is the truthful answer to "these six all meet everything you asked for", and the
  cure for it — a rubric and an AI pass that can actually judge — is one click away.
- **Motion is only ever used to explain.** Cards stagger in so a re-rank reads as a change rather than
  a redraw, score bars fill from zero, and rank badges show ▲2 / ▼1 against the previous round. There
  is no decorative animation, and all of it collapses under `prefers-reduced-motion`.

- **"Have worked at startups" is read as a past-company requirement**, not a current-company one, and
  the parse prompt says to prefer `past_company_types` when it is ambiguous, because it is the more
  forgiving of the two. On the canonical query the model reads it that way and returns four
  candidates rather than six — and that is precisely the kind of thing the refinement loop exists to
  correct in one sentence.
- **Objective filters are a hard gate; the rubric is not.** The refine prompt is told to prefer moving
  a rubric weight over tightening a filter, because a filter deletes people permanently and a weight
  only re-ranks them.
- **The recruiter's own edits are never presented as the AI's work.** Manual edits are tagged
  `Edited by you`, recorded as their own round in the history, and diffed in Java so hand edits are
  explained the same way AI ones are.
