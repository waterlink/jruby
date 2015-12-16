package org.jruby.runtime;

import org.jruby.EvalType;
import org.jruby.RubyModule;
import org.jruby.compiler.Compilable;
import org.jruby.ir.IRClosure;
import org.jruby.ir.IRFlags;
import org.jruby.ir.IRScope;
import org.jruby.ir.interpreter.Interpreter;
import org.jruby.ir.interpreter.InterpreterContext;
import org.jruby.ir.runtime.IRRuntimeHelpers;
import org.jruby.runtime.builtin.IRubyObject;
import org.jruby.util.cli.Options;
import org.jruby.util.log.Logger;
import org.jruby.util.log.LoggerFactory;

public class MixedModeIRBlockBody extends IRBlockBody implements Compilable<CompiledIRBlockBody> {
    private static final Logger LOG = LoggerFactory.getLogger("InterpretedIRBlockBody");
    protected boolean pushScope;
    protected boolean reuseParentScope;
    private boolean displayedCFG = false; // FIXME: Remove when we find nicer way of logging CFG
    private volatile int callCount = 0;
    private InterpreterContext interpreterContext;
    private volatile CompiledIRBlockBody jittedBody;

    public MixedModeIRBlockBody(IRClosure closure, Signature signature) {
        super(closure, signature);
        this.pushScope = true;
        this.reuseParentScope = false;

        // JIT currently JITs blocks along with their method and no on-demand by themselves.  We only
        // promote to full build here if we are -X-C.
        if (!closure.getManager().getInstanceConfig().getCompileMode().shouldJIT()) {
            callCount = -1;
        }
    }

    @Override
    public void setEvalType(EvalType evalType) {
        if (jittedBody == null) this.evalType.set(evalType);
        else jittedBody.setEvalType(evalType);
    }

    @Override
    public void setCallCount(int callCount) {
        this.callCount = callCount;
    }

    @Override
    public void completeBuild(CompiledIRBlockBody blockBody) {
        this.callCount = -1;
        blockBody.evalType = this.evalType; // share with parent
        this.jittedBody = blockBody;
        hasCallProtocolIR = closure.getFlags().contains(IRFlags.HAS_EXPLICIT_CALL_PROTOCOL);
    }

    @Override
    public IRScope getIRScope() {
        return closure;
    }

    public BlockBody getJittedBody() {
        return jittedBody;
    }

    @Override
    public ArgumentDescriptor[] getArgumentDescriptors() {
        return closure.getArgumentDescriptors();
    }

    public InterpreterContext ensureInstrsReady() {
        if (IRRuntimeHelpers.isDebug() && !displayedCFG) {
            LOG.info("Executing '" + closure + "' (pushScope=" + pushScope + ", reuseParentScope=" + reuseParentScope);
            LOG.info(closure.debugOutput());
            displayedCFG = true;
        }

        if (interpreterContext == null) {
            interpreterContext = closure.getInterpreterContext();
            hasCallProtocolIR = false;
        }
        return interpreterContext;
    }

    @Override
    public String getClassName(ThreadContext context) {
        return closure.getName();
    }

    @Override
    public String getName() {
        return closure.getName();
    }

    @Override
    protected IRubyObject callDirect(ThreadContext context, Block block, IRubyObject[] args, Block blockArg) {
        if (callCount >= 0) promoteToFullBuild(context);
        CompiledIRBlockBody jittedBody = this.jittedBody;
        if (jittedBody != null) {
            return jittedBody.callDirect(context, block, args, blockArg);
        }

        context.setCurrentBlockType(Block.Type.PROC);
        return Interpreter.INTERPRET_BLOCK(context, block, null, interpreterContext, args, block.getBinding().getMethod(), blockArg);
    }

    @Override
    protected IRubyObject yieldDirect(ThreadContext context, Block block, IRubyObject[] args, IRubyObject self) {
        if (callCount >= 0) promoteToFullBuild(context);
        CompiledIRBlockBody jittedBody = this.jittedBody;
        if (jittedBody != null) {
            return jittedBody.yieldDirect(context, block, args, self);
        }

        context.setCurrentBlockType(Block.Type.NORMAL);
        return Interpreter.INTERPRET_BLOCK(context, block, self, interpreterContext, args, block.getBinding().getMethod(), Block.NULL_BLOCK);
    }

    protected IRubyObject commonYieldPath(ThreadContext context, Block block, IRubyObject[] args, IRubyObject self, Block blockArg) {
        if (callCount >= 0) promoteToFullBuild(context);

        CompiledIRBlockBody jittedBody = this.jittedBody;
        if (jittedBody != null) {
            return jittedBody.commonYieldPath(context, block, args, self, blockArg);
        }

        InterpreterContext ic = ensureInstrsReady();

        Binding binding = block.getBinding();
        Visibility oldVis = binding.getFrame().getVisibility();
        Frame prevFrame = context.preYieldNoScope(binding);

        // SSS FIXME: Maybe, we should allocate a NoVarsScope/DummyScope for for-loop bodies because the static-scope here
        // probably points to the parent scope? To be verified and fixed if necessary. There is no harm as it is now. It
        // is just wasteful allocation since the scope is not used at all.
        DynamicScope actualScope = binding.getDynamicScope();
        if (ic.pushNewDynScope()) {
            context.pushScope(block.allocScope(actualScope));
        } else if (ic.reuseParentDynScope()) {
            // Reuse! We can avoid the push only if surrounding vars aren't referenced!
            context.pushScope(actualScope);
        }

        // SSS FIXME: Why is self null in non-binding-eval contexts?
        if (self == null || this.evalType.get() == EvalType.BINDING_EVAL) {
            self = useBindingSelf(binding);
        }

        // Clear evaltype now that it has been set on dyn-scope
        block.setEvalType(EvalType.NONE);

        try {
            return Interpreter.INTERPRET_BLOCK(context, block, self, ic, args, binding.getMethod(), blockArg);
        }
        finally {
            // IMPORTANT: Do not clear eval-type in case this is reused in bindings!
            // Ex: eval("...", foo.instance_eval { binding })
            // The dyn-scope used for binding needs to have its eval-type set to INSTANCE_EVAL
            binding.getFrame().setVisibility(oldVis);
            if (ic.popDynScope()) {
                context.postYield(binding, prevFrame);
            } else {
                context.postYieldNoScope(prevFrame);
            }
        }
    }

    protected void promoteToFullBuild(ThreadContext context) {
        if (context.runtime.isBooting()) return; // don't JIT during runtime boot

        synchronized (this) {
            if (callCount >= 0) {
                if (callCount++ >= Options.JIT_THRESHOLD.load()) {
                    callCount = -1;
                    context.runtime.getJITCompiler().buildThresholdReached(context, this);
                }
            }
        }
    }

    public RubyModule getImplementationClass() {
        return null;
    }

}
