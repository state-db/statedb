# Contributing to state-db

Thank you for your interest in state-db. Read this document before you send a pull request.

## License and contributor agreements

state-db is distributed under the [GNU Affero General Public License v3.0 only](LICENSE), identified as `AGPL-3.0-only`.

Contributors retain copyright in their Contributions. The project requires a broad contributor agreement so the named Steward can maintain, distribute, sublicense, and relicense the Project in the future.

Individuals accept the [ICLA](CLA.md). Organizations that own Contributions accept the [CCLA](CCLA.md).

Follow the repository-local process in [CLA_ACCEPTANCE.md](CLA_ACCEPTANCE.md). A checkbox is not sufficient. The maintainer must add the complete acceptance record to `signatures/v1/cla.json` before merge.

If you cannot accept the applicable agreement, open an issue that describes the requested change. A maintainer can decide whether to implement it independently.

## Before you start

For a non-trivial change, open an issue first. Agreement on the design prevents unnecessary work.

## Development workflow

Install the local prerequisites and inspect the environment:

```
brew install mise lima
mise trust mise.toml
./dev doctor
```

Use the credential-free quick tier while you work:

```
./dev quick
./dev check
```

Both commands copy the workspace into a Lima VM. The VM has no host mounts, host-gateway access, or host credentials.

Maintainers can publish the result for a commit with Basecamp's local-CI extension:

```
gh extension install basecamp/gh-signoff
./dev signoff
```

`./dev signoff` recreates the VM before the check. It stops if the commit or worktree changes during the run.

Use host commands only for read-only inspection and file edits. Run build and dependency commands through `./dev`.

## Pull request checklist

- [ ] The applicable ICLA or CCLA acceptance record is in `signatures/v1/cla.json`.
- [ ] The pull request explains the reason for the change.
- [ ] Local CI passes with `./dev check`.
- [ ] New behavior has tests where practical.
- [ ] Commit messages are short and focused.
- [ ] The Contribution does not contain material that you cannot submit under the contributor agreement.
