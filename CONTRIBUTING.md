# Contributing to state-db

Thanks for your interest in contributing. Please read this document before sending a pull request.

## Licensing of contributions

state-db is distributed under the [Elastic License 2.0 with Additional Limitations](LICENSE). The licensor (currently Sharath Madappa, subject to future assignment to a successor entity) also sells commercial licenses to customers who exceed the Free Edition Use Limit.

To make both of those possible, the licensor needs broad rights to your contribution. You retain the copyright in what you contribute, but by submitting a pull request you grant the licensor a perpetual, irrevocable, worldwide license that includes the right to **relicense and sublicense** your contribution under different terms — including paid commercial licenses.

This is the same model used by Elastic, Apache, Sentry, and most commercial open-core projects.

The full agreement is in [CLA.md](CLA.md). Please read it before submitting a PR.

## Signing the CLA

Pull requests are blocked from merging until the PR author has signed the CLA. On your first PR, the CLA Assistant bot will comment with a link to the agreement and a phrase to reply with in order to sign (`I have read the CLA Document and I hereby sign the CLA`). Once signed, your signature is recorded in `signatures/version1/cla.json` in this repository; subsequent PRs from the same GitHub account do not require re-signing.

If you cannot or do not wish to sign the CLA, please open an issue describing the change instead — the maintainer can decide whether to implement it directly.

## Before you start

For non-trivial changes, please open an issue first to align on the approach. This is a small project and review bandwidth is limited; agreement on the design beforehand keeps churn down for both sides.

## Development workflow

All builds, tests, and runs go through the dev container — never run `mvn`, `gradle`, `java`, `npm`, or similar tools directly on the host. See [CLAUDE.md](CLAUDE.md) for the full conventions; in short:

```
./bin/in-container mvn -f modules/pom.xml test
./bin/in-container mvn -f modules/pom.xml -pl app -am quarkus:dev
```

Read-only commands (`git`, `ls`, `rg`, file edits) are fine on the host.

## Pull request checklist

- [ ] You have signed (or are willing to sign) the [CLA](CLA.md).
- [ ] The change has a clear motivation — bug fix, feature, refactor — stated in the PR description.
- [ ] Tests pass via `./bin/in-container mvn -f modules/pom.xml test`.
- [ ] New behavior is covered by tests where practical.
- [ ] Commit messages are short and focused (see existing history for style).
- [ ] You have not introduced material from third-party sources or AI tools that you do not have the right to contribute under the CLA.
