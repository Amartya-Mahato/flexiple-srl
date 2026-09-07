Two things before you build.

### Model and key

Use `gemini-3.6-flash`. Read the key from `LLM_API_KEY` — never write it into a file in the repo, and
make sure `.gitignore` covers `.env`.

### The README has to be flawless for someone starting from nothing

Assume the reader has **no Java, no Maven, and no idea what this stack is**. Write it so they can go
from an empty machine to a running app without guessing:

* how to install a JDK 25 on Windows, macOS and Linux — actual commands, not links
* how to check they got it right, and what to do if `java -version` still shows an old one
* say clearly that Maven is **not** needed, because `mvnw` is committed
* how to get a Gemini API key
* the two commands to run it, written out for PowerShell, cmd and bash separately
* what the first run does differently (downloads, takes longer)
* every environment variable with its default
* a troubleshooting table: port already in use, missing key, wrong model, rate limits, no internet
* how to run the tests

Every command must be copy-pasteable and actually correct on Windows.

### Code standards

* Method names should read like sentences and say what they do — `applyObjectiveFiltersToTalentPool`,
  not `process()` or `handle()`. Same for the JavaScript.
* No abbreviations in identifiers.
* Constructor injection, final fields, records for data, one responsibility per class.
* Named constants instead of magic numbers.
* Comments only where the *why* isn't obvious from the name — never restating the signature.
* Useful server-side logging at the real milestones, and never the API key.

Follow this consistently, not just in the parts I'm likely to read.
