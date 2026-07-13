package io.statedb.pulumiclismoke.faults;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Operator-facing description of a fault-injection scenario, phrased as the guarantee it proves
 * (e.g. "CLI killed mid-update: state converges and the next up recovers"). Collected by {@link
 * StateSafetyReport} into the published State Safety Report.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface FaultScenario {
  String value();
}
