The UI works but feels too plain. Make it feel like a product someone uses all day, not a demo.

Right now chat is the only way to change a search. A real recruiter wants both — the fast AI way *and*
a manual way where they stay in control. Give them every method, easy and advanced.

### Manual search, as a first-class path

* Let me start a search **without the AI at all** — build the filters by hand and search the talent map directly.
* Autocomplete skills and locations from the values that actually exist in `profiles.json`. Don't make me guess spellings.
* Experience range with quick presets, company background toggles, and an **exclude** option too.
* Show a **live match count** as I build the filters, so I know the cost of each criterion before I commit.
* This must work with no API key set.

Both paths should land in the same workspace. A hand-built search should be handoverable to the AI for
rubric ranking, and an AI search should be re-rankable instantly without one. Make it obvious which
ranking I'm looking at, and let me switch in one click.

### Working with the results

* Search within the current results by name, title, company or skill.
* Sort — best fit, most/least experience, name, company.
* Filter by location and company background with facets, showing counts.
* Show only the ones I marked good.
* These should be **instant and client-side**, and must not change the search itself. `Esc` clears them.
* Let me expand a card for the full profile: summary, education, complete history.

### Things I keep wanting and don't have

* **Undo** the last refinement — exactly, not by asking the AI to put it back.
* Add and remove rubric criteria, not just edit the ones the AI wrote.
* Edit the "exclude company types" filter.
* Copy or export the frozen shortlist.
* Keyboard shortcuts for the repetitive parts — move between candidates, mark yes/no, jump to the feedback box.

### Snap and polish

Make it feel fast and alive:

* cards that animate in rather than pop
* score bars that fill
* show me when a candidate moved up or down after a refinement
* a progress indicator while something is running
* toasts for actions that succeeded

Use motion to show what changed, not for decoration. Respect `prefers-reduced-motion`.

Keep it vanilla HTML/CSS/JS — no framework, no build step. Don't break the existing loop, and keep the
tests green.
