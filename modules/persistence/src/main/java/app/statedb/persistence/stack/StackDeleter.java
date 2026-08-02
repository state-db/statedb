package app.statedb.persistence.stack;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/** Deletes a stack by deleting its workspace row and relying on DB-side cascade for child rows. */
@ApplicationScoped
public class StackDeleter {

  private static final String ROOT_STACK_TYPE = "pulumi:pulumi:Stack";

  @Inject StackAssembler assembler;

  @Transactional
  public void delete(StackRef ref, boolean force) {
    if (!force && hasResources(ref)) {
      throw new StackNotEmptyException(ref.orgName(), ref.projectName(), ref.stackName());
    }

    // TODO: when update leases land, reject activeUpdateId unless force=true.
    Workspace.deleteById(ref.workspaceId());
  }

  private boolean hasResources(StackRef ref) {
    JsonNode resources = assembler.assemble(ref.stackId()).deployment().required("resources");
    for (JsonNode resource : resources) {
      if (!ROOT_STACK_TYPE.equals(resource.path("type").asText())) {
        return true;
      }
    }
    return false;
  }
}
