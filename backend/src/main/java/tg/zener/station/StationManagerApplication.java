package tg.zener.station;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
@ConfigurationPropertiesScan
public class StationManagerApplication {

    public static void main(String[] args) {
        // Fuseau de l'application pour la JVM et donc pour les sessions PostgreSQL (current_date, journées)
        String zone = System.getenv("APP_TIMEZONE");
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(zone == null || zone.isBlank() ? "Africa/Lome" : zone));
        SpringApplication.run(StationManagerApplication.class, args);
    }
}
