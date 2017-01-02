package co.aikar.commands.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Created by shane on 1/2/17.
 */
@Retention(RetentionPolicy.RUNTIME)
public @interface Usage {
    String value();
}
