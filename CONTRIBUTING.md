# Contributing to state-db

Thanks for your interest in contributing. Please read this document before sending a pull request.

## Licensing of contributions

state-db is distributed under the [Elastic License 2.0 with Additional Limitations](LICENSE). The licensor (currently Sharath Madappa, subject to future assignment to a successor entity) also sells commercial licenses to customers who exceed the Free Edition Use Limit.

To make both of those possible, the licensor needs broad rights to your contribution. You retain the copyright in what you contribute, but by submitting a pull request you grant the licensor a perpetual, irrevocable, worldwide license that includes the right to **relicense and sublicense** your contribution under different terms — including paid commercial licenses.

This is the same model used by Elastic, Apache, Sentry, and most commercial open-core projects.

The full agreement is in [CLA.md](CLA.md). Please read it before submitting a PR.

## Signing the CLA

Include the CLA checkbox in the pull request template when submitting a contribution. The maintainer
must record accepted contributions in `signatures/version1/cla.json` before merging. A pull request
without that recorded consent must be closed unmerged; the checkbox alone is not sufficient.

If you cannot or do not wish to sign the CLA, please open an issue describing the change instead — the maintainer can decide whether to implement it directly.

## Before you start

For non-trivial changes, please open an issue first to align on the approach. This is a small project and review bandwidth is limited; agreement on the design beforehand keeps churn down for both sides.

## Development workflow

Install the local prerequisites and inspect the environment:

```
brew install mise lima
mise trust mise.toml
./dev doctor
```

Use the credential-free quick tier while iterating. Both commands copy the
workspace into a Lima VM with no host mounts, no host-gateway access, and its
own rootless Docker daemon. Private LAN, tailnet, and link-local destinations are
also blocked while public dependency registries remain reachable:

```
./dev quick
./dev check
```

Maintainers can publish its result on the commit with Basecamp's local-CI extension:

```
gh extension install basecamp/gh-signoff
./dev signoff
```

`./dev signoff` recreates the VM before checking and refuses to publish if HEAD or the worktree
changes during the run. `gh-signoff` requires the commit to be pushed before it publishes the
status. The release command enforces `signoff/ci` on the exact release commit. GitHub branch protection cannot enforce that
status while this private repository is on a plan without private-repository branch protection.

Read-only commands (`git`, `ls`, `rg`, file edits) are fine on the host.

## Pull request checklist

- [ ] You have signed (or are willing to sign) the [CLA](CLA.md).
- [ ] The change has a clear motivation — bug fix, feature, refactor — stated in the PR description.
- [ ] Local CI passes via `./dev check`.
- [ ] New behavior is covered by tests where practical.
- [ ] Commit messages are short and focused (see existing history for style).
- [ ] You have not introduced material from third-party sources or AI tools that you do not have the right to contribute under the CLA.
