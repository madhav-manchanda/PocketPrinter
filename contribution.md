# Contributing to PocketPrint

Thank you for considering a contribution. PocketPrint is maintained as a
project-controlled repository. Contributions are welcome through pull requests
to the official repository.

## Before you contribute

1. Open an issue or discussion for substantial changes so the maintainers can
   agree on the approach.
2. Check existing issues and pull requests to avoid duplicated work.
3. Keep changes focused and explain the problem they solve.
4. Do not include secrets, personal data, printer documents, logs, database
   files, signing keys, generated build output, or private configuration.
5. Run the relevant tests and checks before opening a pull request.

## Contribution workflow

1. Create a temporary fork only for preparing your contribution.
2. Create a focused branch in that fork.
3. Make your changes and add or update tests where appropriate.
4. Run the applicable Android, backend, and frontend checks.
5. Open a pull request against the official PocketPrinter repository.
6. Describe the change, motivation, testing performed, and any security or
   compatibility implications.
7. Keep the pull request updated and respond to review feedback.

A fork created for contribution purposes must not be independently published,
marketed, distributed, or maintained as a separate project. See `license.md`.

## Contributor rights and permission grant

You retain copyright ownership of the original material you contribute.

By submitting a pull request, you grant the PocketPrint project owner a
perpetual, worldwide, non-exclusive, royalty-free, irrevocable (except where
prohibited by law) licence to use, reproduce, modify, adapt, combine, publicly
display, publicly perform, distribute, sublicense, and otherwise maintain your
contribution as part of PocketPrint and its official distributions, in source
or object form and for commercial or non-commercial purposes.

You confirm that:

- You created the contribution or have the authority to submit it and grant
  these permissions.
- Your contribution does not knowingly violate another person's intellectual
  property, privacy, or contractual rights.
- You have identified third-party code or assets and their applicable licences.
- You understand that accepted contributions may be modified, relicensed as
  part of the project, or removed by the project owner.

This grant does not transfer your copyright ownership. It gives the project
owner the permissions needed to incorporate and maintain the contribution.
Do not submit a contribution if you cannot agree to these terms.

## Code and quality expectations

- Follow the existing project structure and style.
- Prefer small, reviewable pull requests.
- Add tests for bug fixes and new behaviour.
- Do not weaken authentication, authorization, input validation, transport
  security, or data isolation.
- Never log passwords, access tokens, pairing codes, document contents, or
  other secrets.
- Update documentation when behaviour, configuration, or APIs change.

## Security issues

Do not report exploitable vulnerabilities in public issues or pull requests.
Follow the private reporting instructions in `security.md`.

## Review and acceptance

Opening a pull request does not guarantee acceptance. Maintainers may request
changes, decline a contribution, or close a pull request. Only contributions
accepted and merged into the official repository become part of the official
PocketPrint codebase.
