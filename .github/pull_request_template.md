## Summary

<!-- 1–3 lines: what does this PR change, and why? -->

## Slice / linked issue

<!-- Reference the slice in design/implementation-slices-v2.md or the issue this addresses, if any. -->

## Manual verification

<!-- One command a reviewer can run through the repository-owned interface. -->

```
./dev check
```

## Checklist

- [ ] I have read and agree to the [Contributor License Agreement](/CLA.md).
- [ ] Build and dependency commands ran through `./dev`; no host credentials or Docker socket were exposed.
- [ ] Local CI passes via `./dev check`.
- [ ] New behavior is covered by tests where practical.
- [ ] Migration files (if any) are numbered sequentially and are idempotent.
- [ ] Commit messages follow the project's terse-subject style (see recent history for examples).
