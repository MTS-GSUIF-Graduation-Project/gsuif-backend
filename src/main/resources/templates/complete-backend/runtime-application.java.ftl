<#ftl strip_whitespace=true encoding="UTF-8">
package ${basePackage};

@org.springframework.boot.autoconfigure.SpringBootApplication
public class Application {
    public static void main(String[] args) {
        org.springframework.boot.SpringApplication.run(Application.class, args);
    }
}
