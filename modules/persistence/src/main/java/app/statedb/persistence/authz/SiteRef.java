package app.statedb.persistence.authz;

/** The singleton state-db deployment targeted by global console administration actions. */
public record SiteRef(String resourceName) {

  public static final SiteRef INSTANCE = new SiteRef(ResourceName.site());
}
