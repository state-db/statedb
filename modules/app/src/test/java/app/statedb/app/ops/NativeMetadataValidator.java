package app.statedb.app.ops;

import com.fasterxml.jackson.databind.JsonNode;
import java.lang.reflect.Array;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class NativeMetadataValidator {

  private NativeMetadataValidator() {}

  static List<String> validate(String location, JsonNode metadata, Set<String> resources) {
    List<String> violations = new ArrayList<>();
    for (JsonNode registration : metadata.path("reflection")) {
      validateReflection(location, registration, violations);
    }
    for (JsonNode resource : metadata.path("resources")) {
      String glob = resource.path("glob").asText();
      var matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);
      if (resources.stream().map(Path::of).noneMatch(matcher::matches)) {
        violations.add(
            "native metadata "
                + location
                + " resource glob "
                + glob
                + " matches no production resource");
      }
    }
    return List.copyOf(violations);
  }

  private static void validateReflection(
      String location, JsonNode registration, List<String> violations) {
    String registeredType = registration.path("type").asText();
    if (registeredType.startsWith("app.statedb.")) {
      violations.add(
          "native metadata "
              + location
              + " centrally registers application type "
              + registeredType
              + "; use a source-local Quarkus native annotation");
    }
    Class<?> owner;
    try {
      owner = type(registeredType);
    } catch (ClassNotFoundException error) {
      violations.add("native metadata " + location + " declares missing type " + registeredType);
      return;
    }
    for (JsonNode method : registration.path("methods")) {
      String name = method.path("name").asText();
      List<String> parameterNames = new ArrayList<>();
      List<Class<?>> parameterTypes = new ArrayList<>();
      boolean missingParameterType = false;
      for (JsonNode parameter : method.path("parameterTypes")) {
        String parameterName = parameter.asText();
        parameterNames.add(parameterName);
        try {
          parameterTypes.add(type(parameterName));
        } catch (ClassNotFoundException error) {
          violations.add("native metadata " + location + " declares missing type " + parameterName);
          missingParameterType = true;
        }
      }
      if (missingParameterType) {
        continue;
      }
      try {
        if ("<init>".equals(name)) {
          owner.getDeclaredConstructor(parameterTypes.toArray(Class<?>[]::new));
        } else {
          owner.getDeclaredMethod(name, parameterTypes.toArray(Class<?>[]::new));
        }
      } catch (NoSuchMethodException error) {
        String kind = "<init>".equals(name) ? "constructor " : "method ";
        String member = "<init>".equals(name) ? registeredType : registeredType + "." + name;
        violations.add(
            "native metadata "
                + location
                + " declares missing "
                + kind
                + member
                + "("
                + String.join(", ", parameterNames)
                + ")");
      }
    }
    for (JsonNode field : registration.path("fields")) {
      String name = field.path("name").asText();
      try {
        owner.getDeclaredField(name);
      } catch (NoSuchFieldException error) {
        violations.add(
            "native metadata "
                + location
                + " declares missing field "
                + registeredType
                + "."
                + name);
      }
    }
  }

  private static Class<?> type(String name) throws ClassNotFoundException {
    if (name.endsWith("[]")) {
      return Array.newInstance(type(name.substring(0, name.length() - 2)), 0).getClass();
    }
    return switch (name) {
      case "boolean" -> boolean.class;
      case "byte" -> byte.class;
      case "char" -> char.class;
      case "double" -> double.class;
      case "float" -> float.class;
      case "int" -> int.class;
      case "long" -> long.class;
      case "short" -> short.class;
      case "void" -> void.class;
      default -> Class.forName(name);
    };
  }
}
