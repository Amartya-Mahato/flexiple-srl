Build the Flexiple "Sourcing Refinement Loop" challenge in the current directory.

Use **Java 25 + latest stable Spring Boot** for the backend and plain **HTML/CSS/JavaScript** for the frontend. No React/Node/npm. The whole app should run with just:

```bash
./mvnw spring-boot:run
```

and be available at `http://localhost:8080`.

There is already a `profiles.json` in the current directory. Load it at runtime and use it as the complete candidate dataset. Don't hardcode or replace the data.

### What the app should do

1. Recruiter enters a free-text requirement, e.g.

   > RDS developers with 4-7 years of experience who have worked at startups, for a role based in Bangalore

2. Backend calls a **real LLM API** and generates:

   * structured filters (skills, experience, location, company type, etc.)
   * a fit rubric

3. Apply the filters locally to `profiles.json`.

4. Send the filtered candidates + rubric to the LLM to score/rank them.

5. Show 4–5 candidates with:

   * name/title/company/location
   * experience
   * skills
   * score
   * a short "why this matches" explanation based on actual profile data
   * Yes / No buttons

6. Add a chat box where the recruiter can say things like:

   > 1 is too junior, 2 and 4 are good. Prefer stronger startup experience.

   Send this feedback to the LLM along with the current filters/rubric/candidates and get updated filters + rubric.

7. Clearly show **what changed and why**, then run the search again.

8. Keep filters and rubric visible and editable.

9. Add a **Freeze Search** button. Once frozen, show the final filters, rubric and ranked shortlist and disable further refinement.

### LLM

Use a real provider such as Gemini, Groq or OpenRouter. Keep the API key in an environment variable such as:

```text
LLM_API_KEY
```

Never expose it to the frontend.

Use structured JSON output and validate everything coming from the LLM. Handle malformed responses, timeouts and rate limits without crashing the app. Keep prompts in the repo, e.g. under:

```text
src/main/resources/prompts/
```

Use separate prompts for parsing the search, scoring candidates and refinement.

### UX

Make the frontend feel like a real recruiter tool, not a demo:

* clean initial search page
* good loading states
* polished candidate cards
* visible filters + rubric
* useful empty state
* friendly error/retry state
* clear refinement changes
* clear frozen/final state
* responsive layout

### Backend

Keep the architecture simple. Something like:

```text
controller
service
llm
filter
domain/dto
```

Use in-memory state; no database/authentication/persistence is needed.

Suggested APIs:

```text
POST /api/search
POST /api/search/refine
POST /api/search/manual-update
POST /api/search/freeze
```

### Important

Candidate explanations must be grounded in the actual `profiles.json` data. Don't let the LLM invent skills, companies, experience or other facts.

Also add a README with:

* setup/run instructions
* required environment variables
* architecture
* where the prompts are
* what you prioritised
* what you intentionally left out

Before finishing, build, test and run the app and fix any compilation/runtime issues.

Keep the implementation focused — this is a 3-hour challenge, so prioritize **UX, the LLM flow, correctness and refinement** over unnecessary infrastructure.
