/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.tooling;

import net.bytebuddy.dynamic.loading.ClassInjector;
import net.bytebuddy.utility.JavaModule;
import java.lang.instrument.Instrumentation;
import java.util.Collections;

public class JavaNetInitializer {
  static void initialize(Instrumentation instrumentation) {
    exportInetAddressModule(instrumentation);
  }

  private static void exportInetAddressModule(Instrumentation instrumentation) {
    JavaModule currentModule = JavaModule.ofType(JavaNetInitializer.class);
    JavaModule javaBase = JavaModule.ofType(ClassLoader.class);
    if (javaBase != null && javaBase.isNamed() && currentModule != null) {
      ClassInjector.UsingInstrumentation.redefineModule(
          instrumentation,
          javaBase,
          Collections.emptySet(),
          Collections.emptyMap(),
          Collections.singletonMap("java.net", Collections.singleton(currentModule)),
          Collections.emptySet(),
          Collections.emptyMap());
    }
  }
}
