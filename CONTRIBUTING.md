# Contributing to PocketPrint

Thanks for helping improve PocketPrint! Bug reports, documentation fixes,
hardware compatibility reports, tests, and code contributions are welcome.

## Ways to contribute

- Report reproducible bugs and unexpected printer behaviour.
- Test printer models and Android devices and share compatibility results.
- Improve setup instructions, architecture documentation, and examples.
- Fix bugs or implement features discussed in an issue.
- Improve automated tests, accessibility, and security.

For substantial changes, open an issue or discussion first so we can agree on
the approach before you spend time implementing it.

## Development workflow

1. Fork the repository and create a focused branch.
2. Make a small, reviewable change.
3. Add or update tests and documentation where appropriate.
4. Run the relevant checks described below.
5. Open a pull request against `main`, explaining the motivation, design,
   testing performed, and any compatibility or security implications.
6. Respond to review feedback and keep the pull request up to date.

Forks and modified copies are permitted under the MIT License. Please make it
clear when a copy is an unofficial fork and do not imply that it is the
official PocketPrint project.

## Project areas

| Area | Main location | Notes |
| --- | --- | --- |
| Android app | `app/` | Kotlin, USB host access, document rendering, print pipeline |
| Backend | `backend/` | FastAPI, authentication, job lifecycle, storage, WebSockets |
| Web dashboard | `frontend/` | React, TypeScript, Vite |
| Deployment | `deploy/`, `docker-compose.yml` | Service and reverse-proxy configuration |
| Documentation | `README.md`, `docs/` | Architecture, setup, hardware validation |

## Checks

Run the checks relevant to the files you changed. Typical commands include:

```bash
# Backend
cd backend
python -m pip install -r requirements.txt
pytest

# Frontend
cd frontend
npm ci
npm run build

# Android (from repository root; requires a compatible JDK and Android SDK)
./gradlew test
```

Some tests require PostgreSQL, Android SDK components, connected hardware, or
environment variables. If a check cannot be run, say so in your pull request
instead of claiming it passed.

## Pull request expectations

- Keep changes focused and explain why they are needed.
- Include tests for bug fixes and new behaviour when practical.
- Update documentation and example configuration when behaviour changes.
- Preserve authentication, authorization, data isolation, and secret handling.
- Do not commit real environment files, API keys, passwords, tokens, signing
  keys, databases, user documents, private logs, or generated build output.
- Disclose new dependencies and their licences.
- Do not submit code or assets you do not have the right to contribute.

## Licensing contributions

By submitting a contribution, you agree that the contribution is provided
under the same MIT License that applies to this repository. You retain
copyright ownership of your contribution. You confirm that you have the right
to submit it and that, to the best of your knowledge, it does not violate
another person's rights.

If a contribution includes third-party code, assets, or data, identify them
and their licence or permission in the pull request. Do not include material
whose licence is incompatible with the project.

## Security issues

Please do not publish exploitable vulnerability details in public issues or
pull requests. Follow [SECURITY.md](./SECURITY.md) for private reporting
instructions.

## Code of conduct

Participation in this project is governed by the
[Code of Conduct](./CODE_OF_CONDUCT.md).
