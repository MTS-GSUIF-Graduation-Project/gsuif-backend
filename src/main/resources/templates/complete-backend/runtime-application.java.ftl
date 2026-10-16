<#ftl strip_whitespace=true encoding="UTF-8">
package ${basePackage};

@org.springframework.boot.autoconfigure.SpringBootApplication
@org.springframework.context.annotation.ComponentScan(basePackages = "${basePackage}",
        excludeFilters = @org.springframework.context.annotation.ComponentScan.Filter(
                type = org.springframework.context.annotation.FilterType.REGEX,
                pattern = "${basePackage}\\.bootstrap\\..*"))
public class Application {
    public static void main(String[] args) {
        if (args.length > 0 && "bootstrap-admin".equals(args[0])) {
            int exit = ${basePackage}.bootstrap.FirstAdminBootstrap.run(args);
            if (exit != 0) System.exit(exit);
            return;
        }
        org.springframework.boot.SpringApplication.run(Application.class, args);
    }
}
