package eg.mts.gsuif.generation;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

final class JavaStructuralGoldenRules {
    record Violation(String rule, String standard, String artifact) { }

    private JavaStructuralGoldenRules() { }

    static List<Violation> validateController(String source) {
        var unit = StaticJavaParser.parse(source);
        List<Violation> violations = new ArrayList<>();
        String artifact = TemplateOnlyGoldenFixture.CONTROLLER_PATH;
        if (!unit.getPackageDeclaration().map(item ->
                item.getNameAsString().equals("com.example.fixture.controller")).orElse(false))
            violations.add(standard("JAVA_PACKAGE", "STD-06", artifact));

        ClassOrInterfaceDeclaration controller = unit.getClassByName("AssetTicketController")
                .orElse(null);
        if (controller == null) {
            violations.add(contract("JAVA_CLASS_NAME", artifact));
            return ordered(violations);
        }
        if (controller.getAnnotationByName("RestController").isEmpty())
            violations.add(standard("JAVA_REST_CONTROLLER_ANNOTATION", "STD-06", artifact));
        if (controller.getImplementedTypes().stream().noneMatch(type ->
                type.getNameAsString().equals("AssetTicketApi")))
            violations.add(contract("JAVA_API_RELATIONSHIP", artifact));
        if (controller.getFields().stream().noneMatch(field -> field.isPrivate()
                && field.isFinal() && field.getVariables().stream().anyMatch(variable ->
                variable.getNameAsString().equals("service")
                        && variable.getType().asString().equals("AssetTicketService"))))
            violations.add(contract("JAVA_SERVICE_FIELD", artifact));
        if (controller.getConstructors().stream().noneMatch(constructor ->
                constructor.getParameters().size() == 1
                        && constructor.getParameter(0).getType().asString().equals("AssetTicketService")))
            violations.add(contract("JAVA_SERVICE_CONSTRUCTOR_RELATIONSHIP", artifact));

        Set<String> required = Set.of("createAssetTicket", "getAssetTicketById", "listAssetTickets",
                "updateAssetTicket", "deleteAssetTicket");
        Set<String> actual = controller.getMethods().stream()
                .map(MethodDeclaration::getNameAsString).collect(java.util.stream.Collectors.toSet());
        if (!actual.containsAll(required))
            violations.add(contract("JAVA_CRUD_METHODS", artifact));

        for (String methodName : required) {
            for (MethodDeclaration method : controller.getMethodsByName(methodName)) {
                if (!method.getType().asString().startsWith("ResponseEntity<ApiResponse<"))
                    violations.add(standard("CONTROLLER_RESPONSE_ENVELOPE", "STD-05", artifact));
            }
        }
        controller.getMethodsByName("createAssetTicket").stream().findFirst()
                .flatMap(MethodDeclaration::getBody).ifPresent(body -> {
                    if (!body.toString().contains("HttpStatus.CREATED"))
                        violations.add(standard("CREATE_STATUS_201", "STD-28", artifact));
                });
        return ordered(violations);
    }

    private static Violation contract(String rule, String artifact) {
        return new Violation(rule, "SCRUM-50/UNMAPPED", artifact);
    }

    private static Violation standard(String rule, String standard, String artifact) {
        return new Violation(rule, standard, artifact);
    }

    private static List<Violation> ordered(List<Violation> violations) {
        violations.sort(Comparator.comparing(Violation::standard)
                .thenComparing(Violation::rule).thenComparing(Violation::artifact));
        return List.copyOf(violations);
    }
}
