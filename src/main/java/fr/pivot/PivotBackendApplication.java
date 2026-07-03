package fr.pivot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Point d'entrée de l'application PIVOT. */
@SpringBootApplication(scanBasePackages = "fr.pivot")
@ConfigurationPropertiesScan("fr.pivot")
public class PivotBackendApplication {

    /** Démarre l'application Spring Boot. */
    public static void main(String[] args) {
        SpringApplication.run(PivotBackendApplication.class, args);
    }
}
