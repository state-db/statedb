## Summary

<!-- 1–3 lines: what does this PR change, and why? -->

## Slice / linked issue

<!-- Reference the slice in design/implementation-slices-v2.md or the issue this addresses, if any. -->

## Manual verification

<!-- One command (or short sequence) a reviewer can paste into the dev container to convince themselves this works. -->

```
./bin/in-container mvn -f modules/pom.xml test
```

## Checklist

- [ ] I have read and agree to the [Contributor License Agreement](/CLA.md). The CLA Assistant bot will guide me through signing on first submission.
- [ ] All build, test, and run commands went through `./bin/in-container` — I did not run `mvn`, `gradle`, `java`, or similar tools directly on the host.
- [ ] Tests pass via `./bin/in-container mvn -f modules/pom.xml test`.
- [ ] New behavior is covered by tests where practical.
- [ ] Migration files (if any) are numbered sequentially and are idempotent.
- [ ] Commit messages follow the project's terse-subject style (see recent history for examples).
