package fr.pivot.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Point d'entrée de l'application PIVOT. */
@SpringBootApplication
public class PivotBackendApplication {

    /** Démarre l'application Spring Boot. */
    public static void main(String[] args) {
        SpringApplication.run(PivotBackendApplication.class, args);
    }
}
