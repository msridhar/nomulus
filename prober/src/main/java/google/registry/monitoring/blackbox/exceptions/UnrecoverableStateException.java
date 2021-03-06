// Copyright 2019 The Nomulus Authors. All Rights Reserved.
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

package google.registry.monitoring.blackbox.exceptions;

/**
 * Exception thrown when error is severe enough that sequence cannot recover, and should be
 * terminated as a result.
 */
public class UnrecoverableStateException extends UndeterminedStateException {

  public UnrecoverableStateException(String msg) {
    super(msg);
  }

  public UnrecoverableStateException(Throwable e) {
    super(e);
  }
}
