package au.com.dius.pact.consumer.junit5

import au.com.dius.pact.consumer.AbstractBaseMockServer
import au.com.dius.pact.consumer.BaseMockServer
import au.com.dius.pact.consumer.ConsumerPactBuilder
import au.com.dius.pact.consumer.MessagePactBuilder
import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.PactTestRun
import au.com.dius.pact.consumer.PactVerificationResult
import au.com.dius.pact.consumer.junit.JUnitTestSupport
import au.com.dius.pact.consumer.mockServer
import au.com.dius.pact.core.model.BasePact
import au.com.dius.pact.core.model.Consumer
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.Provider
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.V4Pact
import au.com.dius.pact.core.model.annotations.Pact
import au.com.dius.pact.core.model.annotations.PactDirectory
import au.com.dius.pact.core.model.annotations.PactFolder
import au.com.dius.pact.core.model.messaging.MessagePact
import au.com.dius.pact.core.support.BuiltToolConfig
import au.com.dius.pact.core.support.Json
import au.com.dius.pact.core.support.expressions.DataType
import au.com.dius.pact.core.support.expressions.ExpressionParser.parseExpression
import com.github.michaelbull.result.unwrap
import mu.KLogging
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.AfterTestExecutionCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback
import org.junit.jupiter.api.extension.Extension
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.ParameterContext
import org.junit.jupiter.api.extension.ParameterResolver
import org.junit.platform.commons.support.AnnotationSupport
import org.junit.platform.commons.support.HierarchyTraversalMode
import org.junit.platform.commons.support.ReflectionSupport
import org.junit.platform.commons.util.AnnotationUtils.isAnnotated
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

class JUnit5MockServerSupport(private val baseMockServer: BaseMockServer) : AbstractBaseMockServer(),
  ExtensionContext.Store.CloseableResource {
  override fun close() {
    baseMockServer.stop()
  }

  override fun start() = baseMockServer.start()
  override fun stop() = baseMockServer.stop()
  override fun waitForServer() = baseMockServer.waitForServer()
  override fun getUrl() = baseMockServer.getUrl()
  override fun getPort() = baseMockServer.getPort()
  override fun <R> runAndWritePact(pact: BasePact, pactVersion: PactSpecVersion, testFn: PactTestRun<R>) =
    baseMockServer.runAndWritePact(pact, pactVersion, testFn)
  override fun validateMockServerState(testResult: Any?) = baseMockServer.validateMockServerState(testResult)
}

class PactConsumerTestExt : Extension, BeforeTestExecutionCallback, BeforeAllCallback, ParameterResolver, AfterTestExecutionCallback, AfterAllCallback {
  override fun supportsParameter(parameterContext: ParameterContext, extensionContext: ExtensionContext): Boolean {
    val providers = lookupProviderInfo(extensionContext)
    val type = parameterContext.parameter.type
    return when {
      providers.any { it.first.providerType == ProviderType.ASYNCH } -> when {
        type.isAssignableFrom(List::class.java) -> true
        type.isAssignableFrom(V4Pact::class.java) -> true
        type.isAssignableFrom(MessagePact::class.java) -> true
        else -> false
      }
      providers.any { it.first.providerType != ProviderType.ASYNCH } -> when {
        type.isAssignableFrom(MockServer::class.java) -> true
        type.isAssignableFrom(RequestResponsePact::class.java) -> true
        type.isAssignableFrom(V4Pact::class.java) -> true
        else -> false
      }
      else -> false
    }
  }

  override fun resolveParameter(parameterContext: ParameterContext, extensionContext: ExtensionContext): Any {
    val type = parameterContext.parameter.type
    val providers = lookupProviderInfo(extensionContext)
    return when {
      providers.size == 1 -> resolveParameterForProvider(providers[0], extensionContext, type)
      parameterContext.isAnnotated(ForProvider::class.java) -> {
        val providerName = parameterContext.findAnnotation(ForProvider::class.java).get().value
        val providerInfo = providers.find { (provider, _) -> provider.providerName == providerName }
        if (providerInfo != null) {
          resolveParameterForProvider(providerInfo, extensionContext, type)
        } else {
          throw UnsupportedOperationException("Did not find a provider with name '${providerName}' for " +
            " parameter: ${parameterContext.index}, ${parameterContext.parameter}")
        }
      }
      else -> {
        throw UnsupportedOperationException("You have setup multiple providers for this test. You need to specify" +
          " which provider the injected value is for with the @ForProvider annotation." +
          " Parameter: ${parameterContext.index}, ${parameterContext.parameter}")
      }
    }
  }

  private fun resolveParameterForProvider(
    providerInfo: Pair<ProviderInfo, String>,
    extensionContext: ExtensionContext,
    type: Class<*>
  ): Any {
    val pact = lookupPact(providerInfo.first, providerInfo.second, extensionContext)
    return when (providerInfo.first.providerType) {
      ProviderType.ASYNCH -> when {
        type.isAssignableFrom(List::class.java) -> pact.interactions
        type.isAssignableFrom(V4Pact::class.java) -> pact.asV4Pact().unwrap()
        type.isAssignableFrom(MessagePact::class.java) -> pact.asMessagePact().unwrap()
        else -> throw UnsupportedOperationException("Could not inject parameter $type into test method")
      }
      else -> when {
        type.isAssignableFrom(MockServer::class.java) -> setupMockServer(providerInfo.first, providerInfo.second, extensionContext)!!
        type.isAssignableFrom(RequestResponsePact::class.java) -> pact.asRequestResponsePact().unwrap()
        type.isAssignableFrom(V4Pact::class.java) -> pact.asV4Pact().unwrap()
        else -> throw UnsupportedOperationException("Could not inject parameter $type into test method")
      }
    }
  }

  override fun beforeAll(context: ExtensionContext) {
    val store = context.getStore(NAMESPACE)
    store.put("executedFragments", ConcurrentHashMap.newKeySet<Method>())
    store.put("pactsToWrite", ConcurrentHashMap<Pair<Consumer, Provider>, Pair<BasePact, PactSpecVersion>>())
  }

  override fun beforeTestExecution(context: ExtensionContext) {
    for ((providerInfo, pactMethod) in lookupProviderInfo(context)) {
      logger.debug { "providerInfo = $providerInfo" }

      if (providerInfo.providerType != ProviderType.ASYNCH) {
        val mockServer = setupMockServer(providerInfo, pactMethod, context)
        mockServer.start()
        mockServer.waitForServer()
      }
    }
  }

  private fun setupMockServer(providerInfo: ProviderInfo, pactMethod: String, context: ExtensionContext): AbstractBaseMockServer {
    val store = context.getStore(NAMESPACE)
    val key = "mockServer:${providerInfo.providerName}"
    return when {
      store[key] != null -> store[key] as AbstractBaseMockServer
      else -> {
        val config = providerInfo.mockServerConfig()
        store.put("mockServerConfig:${providerInfo.providerName}", config)
        val mockServer = mockServer(lookupPact(providerInfo, pactMethod, context), config)
        store.put(key, JUnit5MockServerSupport(mockServer))
        mockServer
      }
    }
  }

  fun lookupProviderInfo(context: ExtensionContext): List<Pair<ProviderInfo, String>> {
    val store = context.getStore(NAMESPACE)
    return when {
      store["providers"] != null -> store["providers"] as List<Pair<ProviderInfo, String>>
      else -> {
        val methodAnnotation = if (AnnotationSupport.isAnnotated(context.requiredTestMethod, PactTestFor::class.java)) {
          logger.debug { "Found @PactTestFor annotation on test method" }
          AnnotationSupport.findAnnotation(context.requiredTestMethod, PactTestFor::class.java).get()
        } else {
          null
        }

        val classAnnotation = if (AnnotationSupport.isAnnotated(context.requiredTestClass, PactTestFor::class.java)) {
          logger.debug { "Found @PactTestFor annotation on test class" }
          AnnotationSupport.findAnnotation(context.requiredTestClass, PactTestFor::class.java).get()
        } else {
          null
        }

        val providers = when {
          classAnnotation != null && methodAnnotation != null ->  {
            val provider = ProviderInfo.fromAnnotation(methodAnnotation)
              .merge(ProviderInfo.fromAnnotation(classAnnotation))
            when {
              methodAnnotation.pactMethods.isNotEmpty() -> {
                methodAnnotation.pactMethods.map {
                  val providerName = providerNameFromPactMethod(it, context)
                  provider.copy(providerName = providerName) to it
                }
              }
              classAnnotation.pactMethods.isNotEmpty() -> {
                classAnnotation.pactMethods.map {
                  val providerName = providerNameFromPactMethod(it, context)
                  provider.copy(providerName = providerName) to it
                }
              }
              else -> listOf(provider to methodAnnotation.pactMethod.ifEmpty { classAnnotation.pactMethod })
            }
          }
          classAnnotation != null -> if (classAnnotation.pactMethods.isNotEmpty()) {
            val annotation = ProviderInfo.fromAnnotation(classAnnotation)
            classAnnotation.pactMethods.map {
              val providerName = providerNameFromPactMethod(it, context)
              annotation.copy(providerName = providerName) to it
            }
          } else {
            listOf(ProviderInfo.fromAnnotation(classAnnotation) to classAnnotation.pactMethod)
          }
          methodAnnotation != null -> if (methodAnnotation.pactMethods.isNotEmpty()) {
            val annotation = ProviderInfo.fromAnnotation(methodAnnotation)
            methodAnnotation.pactMethods.map {
              val providerName = providerNameFromPactMethod(it, context)
              annotation.copy(providerName = providerName) to it
            }
          } else {
            listOf(ProviderInfo.fromAnnotation(methodAnnotation) to methodAnnotation.pactMethod)
          }
          else -> {
            logger.warn { "No @PactTestFor annotation found on test class, using defaults" }
            listOf(ProviderInfo() to "")
          }
        }

        store.put("providers", providers)

        providers
      }
    }
  }

  private fun providerNameFromPactMethod(methodName: String, context: ExtensionContext): String {
    val method = pactMethodAnnotation(null, context, methodName)
    return method!!.getAnnotation(Pact::class.java).provider
  }

  fun lookupPact(
    providerInfo: ProviderInfo,
    pactMethod: String,
    context: ExtensionContext
  ): BasePact {
    val store = context.getStore(NAMESPACE)
    if (store["pact:${providerInfo.providerName}"] == null) {
      val providerName = providerInfo.providerName.ifEmpty { "default" }
      val method = pactMethodAnnotation(providerName, context, pactMethod)

      val providerType = providerInfo.providerType ?: ProviderType.SYNCH
      if (method == null) {
        throw UnsupportedOperationException("No method annotated with @Pact was found on test class " +
          context.requiredTestClass.simpleName + " for provider '${providerInfo.providerName}'")
      } else if (providerType == ProviderType.SYNCH && !JUnitTestSupport.conformsToSignature(method, providerInfo.pactVersion ?: PactSpecVersion.V3)) {
        throw UnsupportedOperationException("Method ${method.name} does not conform to required method signature " +
          "'public [RequestResponsePact|V4Pact] xxx(PactDslWithProvider builder)'")
      } else if (providerType == ProviderType.ASYNCH && !JUnitTestSupport.conformsToMessagePactSignature(method, providerInfo.pactVersion ?: PactSpecVersion.V3)) {
        throw UnsupportedOperationException("Method ${method.name} does not conform to required method signature " +
          "'public [MessagePact|V4Pact] xxx(MessagePactBuilder builder)'")
      }

      val pactAnnotation = AnnotationSupport.findAnnotation(method, Pact::class.java).get()
      val pactConsumer = parseExpression(pactAnnotation.consumer, DataType.STRING)?.toString() ?: pactAnnotation.consumer
      logger.debug {
        "Invoking method '${method.name}' to get Pact for the test " +
          "'${context.testMethod.map { it.name }.orElse("unknown")}'"
      }

      val provider = parseExpression(pactAnnotation.provider, DataType.STRING)?.toString()
      val providerNameToUse = if (provider.isNullOrEmpty()) providerName else provider
      val pact = when (providerType) {
        ProviderType.SYNCH, ProviderType.UNSPECIFIED -> {
          val consumerPactBuilder = ConsumerPactBuilder.consumer(pactConsumer)
          if (providerInfo.pactVersion != null) {
            consumerPactBuilder.pactSpecVersion(providerInfo.pactVersion)
          }
          ReflectionSupport.invokeMethod(method, context.requiredTestInstance,
            consumerPactBuilder.hasPactWith(providerNameToUse)) as BasePact
        }
        ProviderType.ASYNCH -> {
          ReflectionSupport.invokeMethod(method, context.requiredTestInstance,
            MessagePactBuilder(providerInfo.pactVersion ?: PactSpecVersion.V3)
              .consumer(pactConsumer).hasPactWith(providerNameToUse)) as BasePact
        }
      }

      if (providerInfo.pactVersion == PactSpecVersion.V4) {
        pact.asV4Pact().unwrap().interactions.forEach { i ->
         i.comments["testname"] = Json.toJson(context.testClass.map { it.name + "." }.orElse("") +
           context.displayName)
        }
      }

      val executedFragments = store["executedFragments"] as MutableSet<Method>
      executedFragments.add(method)
      store.put("pact:${providerInfo.providerName}", pact)
      return pact
    } else {
      return store["pact:${providerInfo.providerName}"] as BasePact
    }
  }

  private fun pactMethodAnnotation(providerName: String?, context: ExtensionContext, pactMethod: String): Method? {
    val methods = AnnotationSupport.findAnnotatedMethods(context.requiredTestClass, Pact::class.java,
      HierarchyTraversalMode.TOP_DOWN)
    return when {
      pactMethod.isNotEmpty() -> {
        logger.debug { "Looking for @Pact method named '$pactMethod' for provider '$providerName'" }
        methods.firstOrNull { it.name == pactMethod }
      }
      providerName.isNullOrEmpty() -> {
        logger.debug { "Looking for first @Pact method" }
        methods.firstOrNull()
      }
      else -> {
        logger.debug { "Looking for first @Pact method for provider '$providerName'" }
        methods.firstOrNull {
          val pactAnnotationProviderName = AnnotationSupport.findAnnotation(it, Pact::class.java).get().provider
          val annotationProviderName = parseExpression(pactAnnotationProviderName, DataType.STRING)?.toString()
            ?: pactAnnotationProviderName
          annotationProviderName.isEmpty() || annotationProviderName == providerName
        }
      }
    }
  }

  override fun afterTestExecution(context: ExtensionContext) {
    if (!context.executionException.isPresent) {
      val store = context.getStore(NAMESPACE)
      val providers = store["providers"] as List<Pair<ProviderInfo, String>>
      for ((provider, _) in providers) {
        if (provider.providerType == ProviderType.ASYNCH) {
          storePactForWrite(store, provider)
        } else {
          val mockServer = store["mockServer:${provider.providerName}"] as JUnit5MockServerSupport
          Thread.sleep(100) // give the mock server some time to have consistent state
          mockServer.close()
          val result = mockServer.validateMockServerState(null)
          if (result is PactVerificationResult.Ok) {
            storePactForWrite(store, provider)
          } else {
            JUnitTestSupport.validateMockServerResult(result)
          }
        }
      }
    }
  }

  private fun storePactForWrite(store: ExtensionContext.Store, providerInfo: ProviderInfo) {
    @Suppress("UNCHECKED_CAST")
    val pactsToWrite = store["pactsToWrite"] as MutableMap<Pair<Consumer, Provider>, Pair<BasePact, PactSpecVersion>>
    val pact = store["pact:${providerInfo.providerName}"] as BasePact
    val version = providerInfo.pactVersion ?: PactSpecVersion.V3

    pactsToWrite.merge(
      Pair(pact.consumer, pact.provider),
      Pair(pact, version)
    ) { (currentPact, currentVersion), _ ->
      currentPact.mergeInteractions(pact.interactions)
      Pair(currentPact, maxOf(version, currentVersion))
    }
  }

  private fun lookupPactDirectory(context: ExtensionContext): String {
    val pactFolder = AnnotationSupport.findAnnotation(context.requiredTestClass, PactFolder::class.java)
    val pactDirectory = AnnotationSupport.findAnnotation(context.requiredTestClass, PactDirectory::class.java)
    return if (pactFolder.isPresent)
      pactFolder.get().value
    else if (pactDirectory.isPresent)
      pactDirectory.get().value
    else
      BuiltToolConfig.pactDirectory
  }

  override fun afterAll(context: ExtensionContext) {
    if (!context.executionException.isPresent) {
      val store = context.getStore(NAMESPACE)
      val pactDirectory = lookupPactDirectory(context)

      @Suppress("UNCHECKED_CAST")
      val pactsToWrite =
        store["pactsToWrite"] as MutableMap<Pair<Consumer, Provider>, Pair<BasePact, PactSpecVersion>>
      pactsToWrite.values
        .forEach { (pact, version) ->
          logger.debug {
            "Writing pact ${pact.consumer.name} -> ${pact.provider.name} to file " +
              "${pact.fileForPact(pactDirectory)}"
          }
          pact.write(pactDirectory, version)
        }

      val executedFragments = store["executedFragments"] as MutableSet<Method>
      val methods = AnnotationSupport.findAnnotatedMethods(context.requiredTestClass, Pact::class.java,
        HierarchyTraversalMode.TOP_DOWN)
      if (executedFragments.size < methods.size) {
        val nonExecutedMethods = (methods - executedFragments).filter {
          !isAnnotated(it, Disabled::class.java)
        }.joinToString(", ") { it.declaringClass.simpleName + "." + it.name }
        if (nonExecutedMethods.isNotEmpty()) {
          throw AssertionError(
            "The following methods annotated with @Pact were not executed during the test: $nonExecutedMethods" +
              "\nIf these are currently a work in progress, add a @Disabled annotation to the method\n")
        }
      }
    }
  }

  companion object : KLogging() {
    val NAMESPACE: ExtensionContext.Namespace = ExtensionContext.Namespace.create("pact-jvm")
  }
}
