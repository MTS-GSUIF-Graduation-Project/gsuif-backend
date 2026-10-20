package eg.mts.gsuif.generation;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * JavaParser structural golden rules for generated CRUD controllers. Types and annotations are
 * compared by simple name so the rules accept both imported and fully qualified generated code.
 * Each violation names the Standards Checklist item it enforces, or {@link #UNMAPPED} when the
 * assertion protects the generation contract rather than a checklist item.
 */
final class JavaStructuralGoldenRules {
    static final String UNMAPPED = "SCRUM-50/UNMAPPED";
    private static final Pattern PACKAGE_SEGMENT = Pattern.compile("[a-z][a-z0-9]*");
    private static final ParserConfiguration JAVA_21 = new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);

    record Violation(String rule, String standard, String artifact) { }

    /**
     * Expected controller shape. {@code apiInterface} is null when the controller declares its own
     * mappings (the hand-written reference implementation); {@code methodRbac} is false when
     * authorization is not method-level.
     */
    record ControllerShape(String artifact, String packageName, String className, String serviceType,
                           String apiInterface, String createMethod, Set<String> crudMethods,
                           Set<String> idMethods, boolean methodRbac) { }

    private JavaStructuralGoldenRules() { }

    /** Generated code uses records and pattern matching, so parse at the Java 21 language level. */
    static CompilationUnit parse(String source) {
        var result = new JavaParser(JAVA_21).parse(source);
        if (!result.isSuccessful() || result.getResult().isEmpty())
            throw new IllegalArgumentException("Unparseable Java source: " + result.getProblems());
        return result.getResult().get();
    }

    static List<Violation> validateController(String source, ControllerShape shape) {
        var unit = parse(source);
        List<Violation> violations = new ArrayList<>();
        String artifact = shape.artifact();
        String packageName = unit.getPackageDeclaration().map(item -> item.getNameAsString()).orElse("");
        if (!packageName.equalsIgnoreCase(shape.packageName()))
            violations.add(new Violation("JAVA_PACKAGE", "STD-06", artifact));
        String[] segments = packageName.split("\\.");
        if (segments.length < 3 || !java.util.Arrays.stream(segments).allMatch(s -> PACKAGE_SEGMENT.matcher(s).matches()))
            violations.add(new Violation("JAVA_PACKAGE_NAMING", "STD-08", artifact));

        ClassOrInterfaceDeclaration controller = unit.getClassByName(shape.className()).orElse(null);
        if (controller == null) {
            violations.add(new Violation("JAVA_CLASS_NAME", UNMAPPED, artifact));
            return ordered(violations);
        }
        if (!hasAnnotation(controller, "RestController"))
            violations.add(new Violation("JAVA_REST_CONTROLLER_ANNOTATION", "STD-06", artifact));
        if (shape.apiInterface() != null && controller.getImplementedTypes().stream()
                .noneMatch(type -> type.getNameAsString().equals(shape.apiInterface())))
            violations.add(new Violation("JAVA_API_RELATIONSHIP", "STD-27", artifact));
        if (controller.getFields().stream().noneMatch(field -> field.isPrivate() && field.isFinal()
                && field.getVariables().stream().anyMatch(v -> simpleName(v.getType()).equals(shape.serviceType()))))
            violations.add(new Violation("JAVA_SERVICE_FIELD", "STD-06", artifact));
        if (controller.getConstructors().stream().noneMatch(constructor -> constructor.getParameters().stream()
                .anyMatch(parameter -> simpleName(parameter.getType()).equals(shape.serviceType()))))
            violations.add(new Violation("JAVA_SERVICE_CONSTRUCTOR_RELATIONSHIP", "STD-06", artifact));

        Set<String> declared = controller.getMethods().stream().map(MethodDeclaration::getNameAsString)
                .collect(Collectors.toSet());
        if (!declared.containsAll(shape.crudMethods()))
            violations.add(new Violation("JAVA_CRUD_METHODS", UNMAPPED, artifact));

        for (MethodDeclaration method : controller.getMethods()) {
            if (!method.isPublic() || method.isStatic()) continue;
            if (!isResponseEntityOfApiResponse(method.getType()))
                violations.add(new Violation("CONTROLLER_RESPONSE_ENVELOPE", "STD-05", artifact));
        }
        for (String name : shape.crudMethods()) {
            for (MethodDeclaration method : controller.getMethodsByName(name)) {
                if (shape.methodRbac() && !hasAnnotation(method, "PreAuthorize"))
                    violations.add(new Violation("ENDPOINT_RBAC", "STD-20", artifact));
                if (shape.idMethods().contains(name) && method.getParameters().stream().noneMatch(parameter ->
                        parameter.getNameAsString().equals("id") && simpleName(parameter.getType()).equals("UUID")))
                    violations.add(new Violation("UUID_IDENTIFIER", "STD-35", artifact));
            }
        }
        controller.getMethodsByName(shape.createMethod()).stream().findFirst()
                .flatMap(MethodDeclaration::getBody).ifPresent(body -> {
                    if (body.findAll(MethodCallExpr.class).stream().noneMatch(JavaStructuralGoldenRules::isCreatedStatus))
                        violations.add(new Violation("CREATE_STATUS_201", "STD-28", artifact));
                });
        return ordered(violations);
    }

    /** STD-06 layer of a generated class, read from its parsed package declaration. */
    static String layerOf(String source) {
        String packageName = parse(source).getPackageDeclaration()
                .map(item -> item.getNameAsString()).orElse("");
        return packageName.substring(packageName.lastIndexOf('.') + 1);
    }

    private static boolean isCreatedStatus(MethodCallExpr call) {
        if (call.getNameAsString().equals("created")) return true;
        if (!call.getNameAsString().equals("status") || call.getArguments().size() != 1) return false;
        Node argument = call.getArgument(0);
        if (argument instanceof IntegerLiteralExpr literal) return literal.getValue().equals("201");
        if (argument instanceof FieldAccessExpr field) return field.getNameAsString().equals("CREATED");
        return argument instanceof NameExpr name && name.getNameAsString().equals("CREATED");
    }

    private static boolean isResponseEntityOfApiResponse(Type type) {
        if (!(type instanceof ClassOrInterfaceType entity) || !entity.getNameAsString().equals("ResponseEntity"))
            return false;
        var arguments = entity.getTypeArguments().orElse(null);
        return arguments != null && arguments.size() == 1
                && arguments.get(0) instanceof ClassOrInterfaceType envelope
                && envelope.getNameAsString().equals("ApiResponse");
    }

    private static boolean hasAnnotation(NodeWithAnnotations<?> node, String simpleName) {
        for (AnnotationExpr annotation : node.getAnnotations())
            if (annotation.getName().getIdentifier().equals(simpleName)) return true;
        return false;
    }

    private static String simpleName(Type type) {
        return type instanceof ClassOrInterfaceType named ? named.getNameAsString() : type.asString();
    }

    private static List<Violation> ordered(List<Violation> violations) {
        violations.sort(Comparator.comparing(Violation::standard)
                .thenComparing(Violation::rule).thenComparing(Violation::artifact));
        return List.copyOf(violations);
    }
}
