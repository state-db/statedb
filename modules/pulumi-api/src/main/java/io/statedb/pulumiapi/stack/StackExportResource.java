package io.statedb.pulumiapi.stack;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import io.statedb.persistence.authz.Authorize;
import io.statedb.persistence.authz.AuthorizedResource;
import io.statedb.persistence.authz.StackEntityLoader;
import io.statedb.persistence.stack.AssembledDeployment;
import io.statedb.persistence.stack.StackAssembler;
import io.statedb.persistence.stack.StackRef;
import io.statedb.persistence.update.StackUpdate;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

@Path("/pulumi/api/stacks/{org}/{project}/{stack}/export")
public class StackExportResource {

  @Inject StackAssembler assembler;

  @Inject AuthorizedResource authorized;

  @GET
  @Authorize(action = "stack:Read", loader = StackEntityLoader.class)
  @Produces(MediaType.APPLICATION_JSON)
  public ExportDto export(
      @PathParam("org") String org,
      @PathParam("project") String project,
      @PathParam("stack") String stack) {
    StackRef ref = authorized.subject(StackRef.class);
    AssembledDeployment assembled = assembler.assemble(ref.stackId());
    return new ExportDto(assembled.deployment(), assembled.version(), assembled.features());
  }

  /**
   * {@code pulumi stack export --version N}: the CLI appends {@code /N} to the export path.
   * Versions address applied updates (1-based; dry-run rows carry version 0 and never match). An
   * update that has not completed has no {@code end_journal_seq} yet and is not exportable.
   */
  @GET
  @Path("/{version}")
  @Authorize(action = "stack:Read", loader = StackEntityLoader.class)
  @Produces(MediaType.APPLICATION_JSON)
  public ExportDto exportVersion(
      @PathParam("org") String org,
      @PathParam("project") String project,
      @PathParam("stack") String stack,
      @PathParam("version") int version) {
    StackRef ref = authorized.subject(StackRef.class);
    StackUpdate update =
        StackUpdate.<StackUpdate>find(
                "stackId = ?1 and version = ?2 and dryRun = false", ref.stackId(), version)
            .firstResultOptional()
            .orElseThrow(NotFoundException::new);
    if (update.endJournalSeq == null) {
      throw new NotFoundException();
    }
    AssembledDeployment assembled = assembler.assembleAt(ref.stackId(), update.endJournalSeq);
    return new ExportDto(assembled.deployment(), assembled.version(), assembled.features());
  }

  public record ExportDto(
      @JsonProperty("deployment") JsonNode deployment,
      @JsonProperty("version") int version,
      @JsonInclude(JsonInclude.Include.NON_EMPTY) @JsonProperty("features")
          List<String> features) {}
}
