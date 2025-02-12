package org.enso.interpreter.service;

import com.oracle.truffle.api.CompilerDirectives;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.enso.interpreter.instrument.ExpressionExecutionState;
import org.enso.interpreter.instrument.MethodCallsCache;
import org.enso.interpreter.instrument.OneshotExpression;
import org.enso.interpreter.instrument.RuntimeCache;
import org.enso.interpreter.instrument.TypeInfo;
import org.enso.interpreter.instrument.UpdatesSynchronizationState;
import org.enso.interpreter.instrument.VisualizationHolder;
import org.enso.interpreter.instrument.profiling.ExecutionTime;
import org.enso.interpreter.instrument.profiling.ProfilingInfo;
import org.enso.interpreter.node.callable.FunctionCallInstrumentationNode;
import org.enso.interpreter.runtime.callable.UnresolvedSymbol;
import org.enso.interpreter.runtime.data.Type;
import org.enso.interpreter.runtime.library.dispatch.TypeOfNode;
import org.enso.interpreter.runtime.type.Constants;
import org.enso.interpreter.service.ExecutionService.ExpressionCall;
import org.enso.interpreter.service.ExecutionService.ExpressionValue;
import org.enso.interpreter.service.ExecutionService.FunctionCallInfo;
import org.enso.polyglot.debugger.ExecutedVisualization;
import org.enso.polyglot.debugger.IdExecutionService;

final class ExecutionCallbacks implements IdExecutionService.Callbacks {

  private final VisualizationHolder visualizationHolder;
  private final UUID nextExecutionItem;
  private final RuntimeCache cache;
  private final MethodCallsCache methodCallsCache;
  private final UpdatesSynchronizationState syncState;
  private final Map<UUID, FunctionCallInfo> calls = new HashMap<>();
  private final ExpressionExecutionState expressionExecutionState;
  private final Consumer<ExpressionValue> onCachedCallback;
  private final Consumer<ExpressionValue> onComputedCallback;
  private final Consumer<ExpressionCall> functionCallCallback;
  private final Consumer<ExecutedVisualization> onExecutedVisualizationCallback;

  /**
   * Creates callbacks instance.
   *
   * @param visualizationHolder the holder of all visualizations attached to an execution context.
   * @param nextExecutionItem the next item scheduled for execution.
   * @param cache the precomputed expression values.
   * @param methodCallsCache the storage tracking the executed updateCachedResult calls.
   * @param syncState the synchronization state of runtime updates.
   * @param expressionExecutionState the execution state for each expression.
   * @param onComputedCallback the consumer of the computed value events.
   * @param onCachedCallback the consumer of the cached value events.
   * @param functionCallCallback the consumer of function call events.
   * @param onExecutedVisualizationCallback the consumer of an executed visualization result.
   */
  ExecutionCallbacks(
      VisualizationHolder visualizationHolder,
      UUID nextExecutionItem,
      RuntimeCache cache,
      MethodCallsCache methodCallsCache,
      UpdatesSynchronizationState syncState,
      ExpressionExecutionState expressionExecutionState,
      Consumer<ExpressionValue> onCachedCallback,
      Consumer<ExpressionValue> onComputedCallback,
      Consumer<ExpressionCall> functionCallCallback,
      Consumer<ExecutedVisualization> onExecutedVisualizationCallback) {
    this.visualizationHolder = visualizationHolder;
    this.nextExecutionItem = nextExecutionItem;
    this.cache = cache;
    this.methodCallsCache = methodCallsCache;
    this.syncState = syncState;
    this.expressionExecutionState = expressionExecutionState;
    this.onCachedCallback = onCachedCallback;
    this.onComputedCallback = onComputedCallback;
    this.functionCallCallback = functionCallCallback;
    this.onExecutedVisualizationCallback = onExecutedVisualizationCallback;
  }

  @Override
  public Object findCachedResult(IdExecutionService.Info info) {
    UUID nodeId = info.getId();
    Object result = getCachedResult(nodeId);

    if (result != null) {
      executeOneshotExpressions(nodeId, result, info);
    }

    // When executing the call stack we need to capture the FunctionCall of the next (top) stack
    // item in the `functionCallCallback`. We allow to execute the cached `stackTop` value to be
    // able to continue the stack execution, and unwind later from the `onReturnValue` callback.
    if (result != null && !nodeId.equals(nextExecutionItem)) {
      callOnCachedCallback(nodeId, result);
      return result;
    }

    return null;
  }

  @Override
  public void updateCachedResult(IdExecutionService.Info info) {
    Object result = info.getResult();
    TypeInfo resultType = typeOf(result);
    UUID nodeId = info.getId();
    TypeInfo cachedType = cache.getType(nodeId);
    FunctionCallInfo call = functionCallInfoById(nodeId);
    FunctionCallInfo cachedCall = cache.getCall(nodeId);
    ProfilingInfo[] profilingInfo = new ProfilingInfo[] {new ExecutionTime(info.getElapsedTime())};

    ExpressionValue expressionValue =
        new ExpressionValue(
            nodeId, result, resultType, cachedType, call, cachedCall, profilingInfo, false);
    syncState.setExpressionUnsync(nodeId);
    syncState.setVisualizationUnsync(nodeId);

    boolean isPanic = info.isPanic();
    // Panics are not cached because a panic can be fixed by changing seemingly unrelated code,
    // like imports, and the invalidation mechanism can not always track those changes and
    // appropriately invalidate all dependent expressions.
    if (!isPanic) {
      cache.offer(nodeId, result);
      cache.putCall(nodeId, call);
    }
    cache.putType(nodeId, resultType);

    callOnComputedCallback(expressionValue);
    executeOneshotExpressions(nodeId, result, info);
    if (isPanic) {
      // We mark the node as executed so that it is not reported as not executed call after the
      // program execution is complete. If we clear the call from the cache instead, it will mess
      // up the `typeChanged` field of the expression update.
      methodCallsCache.setExecuted(nodeId);
    }
  }

  @CompilerDirectives.TruffleBoundary
  @Override
  public Object onFunctionReturn(IdExecutionService.Info info) {
    FunctionCallInstrumentationNode.FunctionCall fnCall =
        (FunctionCallInstrumentationNode.FunctionCall) info.getResult();
    UUID nodeId = info.getId();
    calls.put(nodeId, FunctionCallInfo.fromFunctionCall(fnCall));
    functionCallCallback.accept(new ExpressionCall(nodeId, fnCall));
    // Return cached value after capturing the enterable function call in `functionCallCallback`
    Object cachedResult = cache.get(nodeId);
    if (cachedResult != null) {
      return cachedResult;
    }
    methodCallsCache.setExecuted(nodeId);
    return null;
  }

  @Override
  @CompilerDirectives.TruffleBoundary
  public Object getExecutionEnvironment(IdExecutionService.Info info) {
    return expressionExecutionState.getExecutionEnvironment(info.getId());
  }

  @CompilerDirectives.TruffleBoundary
  private void callOnComputedCallback(ExpressionValue expressionValue) {
    onComputedCallback.accept(expressionValue);
  }

  @CompilerDirectives.TruffleBoundary
  private void callOnCachedCallback(UUID nodeId, Object result) {
    ExpressionValue expressionValue =
        new ExpressionValue(
            nodeId,
            result,
            cache.getType(nodeId),
            typeOf(result),
            calls.get(nodeId),
            cache.getCall(nodeId),
            new ProfilingInfo[] {ExecutionTime.empty()},
            true);

    onCachedCallback.accept(expressionValue);
  }

  private void executeOneshotExpressions(UUID nodeId, Object result, IdExecutionService.Info info) {
    OneshotExpression oneshotExpression = getOneshotExpression(nodeId);

    if (oneshotExpression != null) {
      Object visualizationResult = null;
      Throwable visualizationError = null;
      try {
        visualizationResult = info.eval(oneshotExpression.expression());
      } catch (Exception exception) {
        visualizationError = exception;
      }

      ExecutedVisualization executedVisualization =
          new ExecutedVisualization(
              visualizationResult, visualizationError, oneshotExpression.id(), nodeId, result);
      callOnExecutedVisualizationCallback(executedVisualization);
    }
  }

  @CompilerDirectives.TruffleBoundary
  private void callOnExecutedVisualizationCallback(ExecutedVisualization executedVisualization) {
    onExecutedVisualizationCallback.accept(executedVisualization);
  }

  @CompilerDirectives.TruffleBoundary
  private Object getCachedResult(UUID nodeId) {
    return cache.get(nodeId);
  }

  @CompilerDirectives.TruffleBoundary
  private OneshotExpression getOneshotExpression(UUID nodeId) {
    return visualizationHolder.getOneshotExpression(nodeId);
  }

  @CompilerDirectives.TruffleBoundary
  private FunctionCallInfo functionCallInfoById(UUID nodeId) {
    return calls.get(nodeId);
  }

  private TypeInfo typeOf(Object value) {
    if (value instanceof UnresolvedSymbol) {
      return TypeInfo.ofType(Constants.UNRESOLVED_SYMBOL);
    }

    if (value != null) {
      final TypeOfNode typeOfNode = TypeOfNode.getUncached();
      final Type[] publicTypes = typeOfNode.findAllTypesOrNull(value, false);

      if (publicTypes != null) {
        final Type[] allTypes = typeOfNode.findAllTypesOrNull(value, true);
        assert Arrays.equals(publicTypes, Arrays.copyOfRange(allTypes, 0, publicTypes.length));
        final Type[] hiddenTypes =
            Arrays.copyOfRange(allTypes, publicTypes.length, allTypes.length);

        final String[] publicTypeNames = new String[publicTypes.length];
        for (var i = 0; i < publicTypes.length; i++) {
          publicTypeNames[i] = getTypeQualifiedName(publicTypes[i]);
        }
        final String[] hiddenTypeNames = new String[hiddenTypes.length];
        for (var i = 0; i < hiddenTypeNames.length; i++) {
          hiddenTypeNames[i] = getTypeQualifiedName(hiddenTypes[i]);
        }

        return new TypeInfo(publicTypeNames, hiddenTypeNames);
      }
    }

    return null;
  }

  @CompilerDirectives.TruffleBoundary
  private static String getTypeQualifiedName(Type t) {
    return t.getQualifiedName().toString();
  }
}
