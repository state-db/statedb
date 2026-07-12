@id("ServiceAccountAccess")
// The service-account (CI) grant set. Role::"service" sits outside the
// viewer ⊂ member ⊂ owner ladder, so this policy is the ONLY thing a CI token can
// do — refining it never reshapes human permissions and vice versa.
//
// The set is exactly what a deploy pipeline runs (verified against the recorded
// `pulumi up/preview/refresh/destroy/output` fixtures): read + decrypt (the
// ReadOnlyAccess bundle), acquire the update lock, and encrypt new secret values.
// Stack lifecycle (Create / Delete / Rename / SetTags) stays with humans.
permit(
  principal in Role::"service",
  action in [
    Action::"stack:ReadOnlyAccess",
    Action::"stack:Lock",
    Action::"stack:EncryptSecrets"
  ],
  resource
);
