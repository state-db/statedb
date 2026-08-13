# cedar-java distribution notices

These files come from the `cedar-policy/cedar-java` `v4.10.0` tag. Keep this directory's version
aligned with `cedar-java.version` in `modules/pom.xml`. The uber JAR embeds native Cedar/JNE
libraries but does not embed the repository-level notice files, so release images carry them
separately under `/licenses`.

`SHA256SUMS` pins both redistributed Linux JNI libraries from the resolved 4.10.0 uber JAR and
these legal files. The native container build rejects a dependency whose selected library differs.
