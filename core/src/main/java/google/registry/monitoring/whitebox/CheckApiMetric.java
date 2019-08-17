// Copyright 2018 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.monitoring.whitebox;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.auto.value.AutoValue;
import google.registry.util.Clock;
import java.util.Optional;
import org.joda.time.DateTime;

/** A value class for recording attributes of a domain check metric. */
@AutoValue
public abstract class CheckApiMetric {

  /** Price tier of a domain name. */
  public enum Tier {
    STANDARD("standard"),
    PREMIUM("premium");

    private final String displayLabel;

    Tier(String displayLabel) {
      this.displayLabel = displayLabel;
    }

    public String getDisplayLabel() {
      return displayLabel;
    }
  }

  /** Availability status of a domain. */
  public enum Availability {
    RESERVED("reserved"),
    REGISTERED("registered"),
    AVAILABLE("available");

    private final String displayLabel;

    Availability(String displayLabel) {
      this.displayLabel = displayLabel;
    }

    public String getDisplayLabel() {
      return displayLabel;
    }
  }

  /** Status of the CheckApi command. */
  public enum Status {
    SUCCESS("success"),
    INVALID_NAME("invalid_name"),
    INVALID_REGISTRY_PHASE("invalid_registry_phase"),
    UNKNOWN_ERROR("unknown_error");

    private final String displayLabel;

    Status(String displayLabel) {
      this.displayLabel = displayLabel;
    }

    public String getDisplayLabel() {
      return displayLabel;
    }
  }

  public abstract DateTime startTimestamp();

  public abstract DateTime endTimestamp();

  public abstract Status status();

  public abstract Optional<Tier> tier();

  public abstract Optional<Availability> availability();

    public static @org.checkerframework.checker.objectconstruction.qual.CalledMethods("startTimestamp") Builder builder(Clock clock) {
    return new AutoValue_CheckApiMetric.Builder().startTimestamp(clock.nowUtc()).setClock(clock);
  }

  CheckApiMetric() {}

  /** Builder for {@link CheckApiMetric}. */
  @AutoValue.Builder
  public abstract static class Builder {

    private Clock clock;

    /** Saves the {@link Clock} for end-time determination. */
    Builder setClock(Clock clock) {
      checkNotNull(clock, "clock");
      this.clock = clock;
      return this;
    }

      public CheckApiMetric build(@org.checkerframework.checker.objectconstruction.qual.CalledMethods({"startTimestamp","status"}) Builder this) {
      return this.endTimestamp(clock.nowUtc()).autoBuild();
    }

    abstract Builder startTimestamp(DateTime startTimestamp);

    abstract Builder endTimestamp(DateTime endTimestamp);

    public abstract Builder status(Status status);

    public abstract Builder tier(Tier tier);

    public abstract Builder availability(Availability availability);

    abstract CheckApiMetric autoBuild();
  }
}
