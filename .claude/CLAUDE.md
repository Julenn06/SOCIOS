# Android Development — Project Rules

## 1. General principles

- Act as a senior Android development team focused on production-quality applications.

- Inspect the existing project before making changes.

- Respect the current architecture, naming conventions and dependencies unless a change is justified.

- Never invent files, dependencies, APIs, test results or capabilities.

## 2. Skills

- Discover and use relevant skills from `.claude/skills/`.

- Consult each skill's `SKILL.md` when its instructions are relevant to the current task.

- Combine skills according to the task: design, Android implementation, architecture, testing or optimization.

- Avoid applying conflicting design systems or duplicating instructions.

- Do not claim to have used a skill unless you have actually consulted its relevant instructions.

## 3. Android development

- Prefer Kotlin and Jetpack Compose for new native Android interfaces unless project requirements dictate otherwise.

- Use Material 3 as the foundation for a consistent, polished and distinctive design system.

- Prioritize usability, visual hierarchy, typography, spacing, accessibility, responsive layouts and meaningful animations.

- Handle loading, empty, success and error states.

- Follow appropriate Android architecture, lifecycle, concurrency, persistence and security practices.

- Verify library versions and compatibility before introducing dependencies.

## 4. Code quality

- Write maintainable, modular and readable code.

- Avoid unnecessary abstractions, duplicated logic and unrelated refactoring.

- Preserve existing functionality unless changes are explicitly required.

- Make focused changes and explain important architectural decisions briefly.

## 5. Verification

- Run relevant builds and tests when possible.

- Investigate and fix errors introduced by changes.

- Distinguish verified results from assumptions.

- Never claim that the application compiles, tests pass or UI has been inspected unless that verification actually occurred.

## 6. Token efficiency

- Keep responses concise and action-oriented.

- Inspect only relevant files and avoid dumping entire files or directories.

- Load specialized skills only when needed.

- Avoid repetitive explanations, unnecessary tool calls and redundant documentation.

- Prefer small, verifiable changes over large speculative implementations.

- Never sacrifice correctness, security or essential testing merely to reduce token usage.

## 7. Working process

- For small, clear tasks, proceed directly.

- For substantial features, first identify requirements, relevant files, design implications and implementation steps.

- Implement the agreed scope, verify it and summarize the files changed, checks performed and any outstanding issues.

## 8. Publishing (default, no need to ask)

Every finished and verified change to the repository is published without asking first (explicit instruction from
the user, 2026-11-19). Only destructive operations still need confirmation (deleting branches or data, force-push,
rewriting history).

- **Android app** (`app/`, `gradle/`, build files): commit and push to `main`, then push the next tag `vX.Y.Z`
  (patch for fixes and small adjustments, minor for new features). The "Build and Release" workflow runs the tests
  and publishes the GitHub Release with the signed APK, which the app offers as an update. Push `main` first and the
  tag afterwards. Wait for the workflow and confirm the release and its APK exist. The release notes are the
  `feat`/`fix`/`perf` commit titles that touch the app since the previous tag: write them for the user, in Spanish.
- **Web PWA** (`pwa/`): bump the version with `node pwa/herramientas/version.mjs X.Y.Z "novedad"`, commit and push to
  `main`. The "Publicar la web" workflow runs the tests and Hostinger deploys https://socios.jfl06studio.com. Confirm
  the published `version.json`.
- **Web server** (`servidor/`): commit and push to `main`. The "Publicar el servidor" workflow runs the tests and
  Hostinger deploys https://socios-api.jfl06studio.com. Confirm `/api/salud`.
- The Android app and the web are separate products: the app does not use the web server. A change requested for
  both is published in both.

