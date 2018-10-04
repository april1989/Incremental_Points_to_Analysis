/*******************************************************************************
 * Copyright (C) 2017 Bozhen Liu, Jeff Huang.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Bozhen Liu, Jeff Huang - initial API and implementation
 ******************************************************************************/
package edu.tamu.wala.increpta.ipa.callgraph.propagation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import com.ibm.wala.analysis.reflection.IllegalArgumentExceptionContext;
import com.ibm.wala.cfg.ControlFlowGraph;
import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IField;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.classLoader.Language;
import com.ibm.wala.classLoader.NewSiteReference;
import com.ibm.wala.classLoader.SyntheticClass;
import com.ibm.wala.fixpoint.IVariable;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.CallGraphBuilder;
import com.ibm.wala.ipa.callgraph.CallGraphBuilderCancelException;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.ContextSelector;
import com.ibm.wala.ipa.callgraph.Entrypoint;
import com.ibm.wala.ipa.callgraph.IAnalysisCacheView;
import com.ibm.wala.ipa.callgraph.impl.AbstractRootMethod;
import com.ibm.wala.ipa.callgraph.impl.ExplicitCallGraph;
import com.ibm.wala.ipa.callgraph.propagation.FilteredPointerKey;
import com.ibm.wala.ipa.callgraph.propagation.IPointerOperator;
import com.ibm.wala.ipa.callgraph.propagation.IPointsToSolver;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKeyFactory;
import com.ibm.wala.ipa.callgraph.propagation.PointerAnalysis;
import com.ibm.wala.ipa.callgraph.propagation.PointerKey;
import com.ibm.wala.ipa.callgraph.propagation.PointerKeyFactory;
import com.ibm.wala.ipa.callgraph.propagation.SSAContextInterpreter;
import com.ibm.wala.ipa.callgraph.propagation.ZeroLengthArrayInNode;
import com.ibm.wala.ipa.callgraph.propagation.rta.RTAContextInterpreter;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.ssa.DefUse;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSAAbstractInvokeInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.types.TypeReference;
import com.ibm.wala.util.CancelException;
import com.ibm.wala.util.CancelRuntimeException;
import com.ibm.wala.util.MonitorUtil.IProgressMonitor;
import com.ibm.wala.util.collections.HashSetFactory;
import com.ibm.wala.util.debug.Assertions;
import com.ibm.wala.util.intset.IntSet;
import com.ibm.wala.util.intset.IntSetAction;
import com.ibm.wala.util.intset.IntSetUtil;
import com.ibm.wala.util.intset.MutableIntSet;
import com.ibm.wala.util.intset.MutableSharedBitVectorIntSet;
import com.ibm.wala.util.warnings.Warning;
import com.ibm.wala.util.warnings.Warnings;

import edu.tamu.wala.increpta.callgraph.impl.IPAExplicitCallGraph;
import edu.tamu.wala.increpta.ipa.callgraph.propagation.IPASSAPropagationCallGraphBuilder.ConstraintVisitor;
import edu.tamu.wala.increpta.operators.IPAAssignOperator;
import edu.tamu.wala.increpta.operators.IPAUnaryOperator;
import edu.tamu.wala.increpta.operators.IPAUnarySideEffect;
import edu.tamu.wala.increpta.pointerkey.IPAFilteredPointerKey;
import edu.tamu.wala.increpta.pointerkey.IPAPointerKeyFactory;
import edu.tamu.wala.increpta.util.IPAAbstractFixedPointSolver;

public abstract class IPAPropagationCallGraphBuilder implements CallGraphBuilder<InstanceKey> {

	private final static boolean DEBUG_ALL = false;

	public final static boolean DEBUG_ASSIGN = DEBUG_ALL | false;

	private final static boolean DEBUG_ARRAY_LOAD = DEBUG_ALL | false;

	private final static boolean DEBUG_ARRAY_STORE = DEBUG_ALL | false;

	private final static boolean DEBUG_FILTER = DEBUG_ALL | false;

	final protected static boolean DEBUG_GENERAL = DEBUG_ALL | false;

	private final static boolean DEBUG_GET = DEBUG_ALL | false;

	private final static boolean DEBUG_PUT = DEBUG_ALL | false;

	private final static boolean DEBUG_ENTRYPOINTS = DEBUG_ALL | false;

	/**
	 * Meta-data regarding how pointers are modeled
	 */
	protected IPAPointerKeyFactory pointerKeyFactory;

	/**
	 * The object that represents the java.lang.Object class
	 */
	final private IClass JAVA_LANG_OBJECT;

	/**
	 * Governing class hierarchy
	 */
	public final IClassHierarchy cha;

	/**
	 * Special rules for bypassing Java calls
	 */
	final protected AnalysisOptions options;

	/**
	 * Cache of IRs and things
	 */
	private final IAnalysisCacheView analysisCache;

	/**
	 * Set of nodes that have already been traversed for constraints
	 */
	final private Set<CGNode> alreadyVisited = HashSetFactory.make();

	public Set<CGNode> getAlreadyVisited(){
		return alreadyVisited;
	}

	/**
	 * At any given time, the set of nodes that have been discovered but not yet processed for constraints
	 */
	private Set<CGNode> discoveredNodes = HashSetFactory.make();

	/**
	 * Set of calls (CallSiteReferences) that are created by entrypoints
	 */
	final protected Set<CallSiteReference> entrypointCallSites = HashSetFactory.make();

	/**
	 * The system of constraints used to build this graph
	 */
	protected IPAPropagationSystem system;

	public IPAPropagationSystem getSystem() {
		return system;
	}

	/**
	 * Algorithm used to solve the system of constraints
	 */
	private IPointsToSolver solver;

	/**
	 * The call graph under construction
	 */
	protected final IPAExplicitCallGraph callGraph;

	/**
	 * Singleton operator for assignments
	 */
	public final static IPAAssignOperator assignOperator = new IPAAssignOperator();

	/**
	 * singleton operator for filter
	 */
	public final IPAFilterOperator filterOperator = new IPAFilterOperator();

	/**
	 * singleton operator for inverse filter
	 */
	protected final InverseFilterOperator inverseFilterOperator = new InverseFilterOperator();

	/**
	 * An object which interprets methods in context
	 */
	private SSAContextInterpreter contextInterpreter;

	/**
	 * A context selector which may use information derived from the propagation-based dataflow.
	 */
	protected ContextSelector contextSelector;

	/**
	 * An object that abstracts how to model instances in the heap.
	 */
	protected InstanceKeyFactory instanceKeyFactory;

	/**
	 * Algorithmic choice: should the GetfieldOperator and PutfieldOperator cache its previous history to reduce work?
	 */
	final private boolean rememberGetPutHistory = true;

	/**
	 * @param cha governing class hierarchy
	 * @param options governing call graph construction options
	 * @param pointerKeyFactory factory which embodies pointer abstraction policy
	 */
	protected IPAPropagationCallGraphBuilder(IMethod abstractRootMethod, AnalysisOptions options, IAnalysisCacheView cache,
			IPAPointerKeyFactory pointerKeyFactory) {
		if (abstractRootMethod == null) {
			throw new IllegalArgumentException("cha is null");
		}
		if (options == null) {
			throw new IllegalArgumentException("options is null");
		}
		assert cache != null;
	    this.cha = abstractRootMethod.getClassHierarchy();
		this.options = options;
		this.analysisCache = cache;
		// we need pointer keys to handle reflection
		assert pointerKeyFactory != null;
		this.pointerKeyFactory = pointerKeyFactory;
	    callGraph = createEmptyCallGraph(abstractRootMethod, options);
		try {
			callGraph.init();
		} catch (CancelException e) {
			if (DEBUG_GENERAL) {
				System.err.println("Could not initialize the call graph due to node number constraints: " + e.getMessage());
			}
		}
		callGraph.setInterpreter(contextInterpreter);
		JAVA_LANG_OBJECT = cha.lookupClass(TypeReference.JavaLangObject);
	}

	protected IPAExplicitCallGraph createEmptyCallGraph(IMethod abstractRootMethod, AnalysisOptions options) {
		return new IPAExplicitCallGraph(abstractRootMethod, options, getAnalysisCache());
	}

	/**
	 * @return true iff the klass represents java.lang.Object
	 */
	protected boolean isJavaLangObject(IClass klass) {
		return (klass.getReference().equals(TypeReference.JavaLangObject));
	}

	public CallGraph makeCallGraph(AnalysisOptions options) throws IllegalArgumentException, CancelException {
		return makeCallGraph(options, null);
	}

	/*
	 * @see com.ibm.wala.ipa.callgraph.CallGraphBuilder#makeCallGraph(com.ibm.wala.ipa.callgraph.AnalysisOptions)
	 */
	@Override
	public CallGraph makeCallGraph(AnalysisOptions options, IProgressMonitor monitor) throws IllegalArgumentException,
	CallGraphBuilderCancelException {
		if (options == null) {
			throw new IllegalArgumentException("options is null");
		}
		system = makeSystem(options);

		if (DEBUG_GENERAL) {
			System.err.println("Enter makeCallGraph!");
		}

		if (DEBUG_GENERAL) {
			System.err.println("Initialized call graph");
		}

		system.setMinEquationsForTopSort(options.getMinEquationsForTopSort());
		system.setTopologicalGrowthFactor(options.getTopologicalGrowthFactor());
		system.setMaxEvalBetweenTopo(options.getMaxEvalBetweenTopo());

		discoveredNodes = HashSetFactory.make();
		discoveredNodes.add(callGraph.getFakeRootNode());

		// Set up the initially reachable methods and classes
		for (Iterator it = options.getEntrypoints().iterator(); it.hasNext();) {
			Entrypoint E = (Entrypoint) it.next();
			if (DEBUG_ENTRYPOINTS) {
				System.err.println("Entrypoint: " + E);
			}
			SSAAbstractInvokeInstruction call = E.addCall((AbstractRootMethod) callGraph.getFakeRootNode().getMethod());

			if (call == null) {
				Warnings.add(EntrypointResolutionWarning.create(E));
			} else {
				entrypointCallSites.add(call.getCallSite());
			}
		}

		/** BEGIN Custom change: throw exception on empty entry points. This is a severe issue that should not go undetected! */
		if (entrypointCallSites.isEmpty()) {
			throw new IllegalStateException("Could not create a entrypoint callsites: " +   Warnings.asString());
		}
		/** END Custom change: throw exception on empty entry points. This is a severe issue that should not go undetected! */
		customInit();

		solver = makeSolver();
		try {
			solver.solve(monitor);
		} catch (CancelException e) {
			e.printStackTrace();
		} catch (CancelRuntimeException e) {
			e.printStackTrace();
		}

		return callGraph;
	}

	protected IPAPropagationSystem makeSystem(@SuppressWarnings("unused") AnalysisOptions options) {
		return new IPAPropagationSystem(callGraph, pointerKeyFactory, instanceKeyFactory);
	}

	protected abstract IPointsToSolver makeSolver();

	/**
	 * A warning for when we fail to resolve a call to an entrypoint
	 */
	private static class EntrypointResolutionWarning extends Warning {

		final Entrypoint entrypoint;

		EntrypointResolutionWarning(Entrypoint entrypoint) {
			super(Warning.SEVERE);
			this.entrypoint = entrypoint;
		}

		@Override
		public String getMsg() {
			return getClass().toString() + " : " + entrypoint;
		}

		public static EntrypointResolutionWarning create(Entrypoint entrypoint) {
			return new EntrypointResolutionWarning(entrypoint);
		}
	}

	protected void customInit() {
	}

	/**
	 * Add constraints for a node.
	 * @param monitor
	 *
	 * @return true iff any new constraints are added.
	 */
	protected abstract boolean addConstraintsFromNode(CGNode n, IProgressMonitor monitor) throws CancelException;

	/**
	 * Add constraints from newly discovered nodes. Note: the act of adding constraints may discover new nodes, so this routine is
	 * iterative.
	 *
	 * @return true iff any new constraints are added.
	 * @throws CancelException
	 */
	public boolean addConstraintsFromNewNodes(IProgressMonitor monitor) throws CancelException {
		boolean result = false;
		while (!discoveredNodes.isEmpty()) {
			Iterator<CGNode> it = discoveredNodes.iterator();
			discoveredNodes = HashSetFactory.make();
			while (it.hasNext()) {
				CGNode n = it.next();
				result |= addConstraintsFromNode(n, monitor);
			}
		}
		return result;
	}

	/**
	 * @return the PointerKey that acts as a representative for the class of pointers that includes the local variable identified by
	 *         the value number parameter.
	 */
	public PointerKey getPointerKeyForLocal(CGNode node, int valueNumber) {
		return pointerKeyFactory.getPointerKeyForLocal(node, valueNumber);
	}

	/**
	 * @return the PointerKey that acts as a representative for the class of pointers that includes the local variable identified by
	 *         the value number parameter.
	 */
	public IPAFilteredPointerKey getFilteredPointerKeyForLocal(CGNode node, int valueNumber, IPAFilteredPointerKey.IPATypeFilter filter) {
		assert filter != null;
		return pointerKeyFactory.getFilteredPointerKeyForLocal(node, valueNumber, filter);
	}//bz

	public FilteredPointerKey getFilteredPointerKeyForLocal(CGNode node, int valueNumber, FilteredPointerKey.TypeFilter filter) {
		assert filter != null;
		return pointerKeyFactory.getFilteredPointerKeyForLocal(node, valueNumber, filter);
	}

	public IPAFilteredPointerKey getFilteredPointerKeyForLocal(CGNode node, int valueNumber, IClass filter) {
		return getFilteredPointerKeyForLocal(node, valueNumber, new IPAFilteredPointerKey.SingleClassFilter(filter));
	}

	public IPAFilteredPointerKey getFilteredPointerKeyForLocal(CGNode node, int valueNumber, InstanceKey filter) {
		return getFilteredPointerKeyForLocal(node, valueNumber, new IPAFilteredPointerKey.SingleInstanceFilter(filter));
	}

	/**
	 * @return the PointerKey that acts as a representative for the class of pointers that includes the return value for a node
	 */
	public PointerKey getPointerKeyForReturnValue(CGNode node) {
		return pointerKeyFactory.getPointerKeyForReturnValue(node);
	}

	/**
	 * @return the PointerKey that acts as a representative for the class of pointers that includes the exceptional return value
	 */
	public PointerKey getPointerKeyForExceptionalReturnValue(CGNode node) {
		return pointerKeyFactory.getPointerKeyForExceptionalReturnValue(node);
	}

	/**
	 * @return the PointerKey that acts as a representative for the class of pointers that includes the contents of the static field
	 */
	public PointerKey getPointerKeyForStaticField(IField f) {
		assert f != null : "null FieldReference";
		return pointerKeyFactory.getPointerKeyForStaticField(f);
	}

	/**
	 * @return the PointerKey that acts as a representation for the class of pointers that includes the given instance field. null if
	 *         there's some problem.
	 * @throws IllegalArgumentException if I is null
	 * @throws IllegalArgumentException if field is null
	 */
	public PointerKey getPointerKeyForInstanceField(InstanceKey I, IField field) {
		if (field == null) {
			throw new IllegalArgumentException("field is null");
		}
		if (I == null) {
			throw new IllegalArgumentException("I is null");
		}
		IClass t = field.getDeclaringClass();
		IClass C = I.getConcreteType();
		if (!(C instanceof SyntheticClass)) {
			if (!getClassHierarchy().isSubclassOf(C, t)) {
				return null;
			}
		}

		return pointerKeyFactory.getPointerKeyForInstanceField(I, field);
	}

	/**
	 * TODO: expand this API to differentiate between different array indices
	 *
	 * @param I an InstanceKey representing an abstract array
	 * @return the PointerKey that acts as a representation for the class of pointers that includes the given array contents, or null
	 *         if none found.
	 * @throws IllegalArgumentException if I is null
	 */
	public PointerKey getPointerKeyForArrayContents(InstanceKey I) {
		if (I == null) {
			throw new IllegalArgumentException("I is null");
		}
		IClass C = I.getConcreteType();
		if (!C.isArrayClass()) {
			assert false : "illegal arguments: " + I;
		}
		return pointerKeyFactory.getPointerKeyForArrayContents(I);
	}

	/**
	 * Handle assign of a particular exception instance into an exception variable
	 *
	 * @param exceptionVar points-to set for a variable representing a caught exception
	 * @param catchClasses set of TypeReferences that the exceptionVar may catch
	 * @param e a particular exception instance
	 */
	protected void assignInstanceToCatch(PointerKey exceptionVar, Set<IClass> catchClasses, InstanceKey e) {
		if (catches(catchClasses, e.getConcreteType(), cha)) {
			system.newConstraint(exceptionVar, e);
		}
	}

	/**
	 * Generate a set of constraints to represent assignment to an exception variable in a catch clause. Note that we use
	 * FilterOperator to filter out types that the exception handler doesn't catch.
	 *
	 * @param exceptionVar points-to set for a variable representing a caught exception
	 * @param catchClasses set of TypeReferences that the exceptionVar may catch
	 * @param e points-to-set representing a thrown exception that might be caught.
	 */
	protected void addAssignmentsForCatchPointerKey(PointerKey exceptionVar, Set<IClass> catchClasses, PointerKey e) {
		if (DEBUG_GENERAL) {
			System.err.println("addAssignmentsForCatch: " + catchClasses);
		}
		// this is tricky ... we want to filter based on a number of classes ... so we can't
		// just used a IPAFilteredPointerKey for the exceptionVar. Instead, we create a new
		// "typed local" for each catch class, and coalesce the results using
		// assignment
		for (IClass c : catchClasses) {
			if (c.getReference().equals(c.getClassLoader().getLanguage().getThrowableType())) {
				system.newConstraint(exceptionVar, assignOperator, e);
			} else {
				IPAFilteredPointerKey typedException = TypedPointerKey.make(exceptionVar, c);
				system.newConstraint(typedException, filterOperator, e);
				system.newConstraint(exceptionVar, assignOperator, typedException);
			}
		}
	}

	/**
	 * A warning for when we fail to resolve a call to an entrypoint
	 */
	@SuppressWarnings("unused")
	private static class ExceptionLookupFailure extends Warning {

		final TypeReference t;

		ExceptionLookupFailure(TypeReference t) {
			super(Warning.SEVERE);
			this.t = t;
		}

		@Override
		public String getMsg() {
			return getClass().toString() + " : " + t;
		}

		public static ExceptionLookupFailure create(TypeReference t) {
			return new ExceptionLookupFailure(t);
		}
	}

	/**
	 * A pointer key that delegates to an untyped variant, but adds a type filter
	 */
	public final static class TypedPointerKey implements IPAFilteredPointerKey {

		private final IClass type;

		private final PointerKey base;

		static TypedPointerKey make(PointerKey base, IClass type) {
			assert type != null;
			return new TypedPointerKey(base, type);
		}

		private TypedPointerKey(PointerKey base, IClass type) {
			this.type = type;
			this.base = base;
			assert type != null;
			assert !(type instanceof IPAFilteredPointerKey);
		}

		/*
		 * @see com.ibm.wala.ipa.callgraph.propagation.IPAFilteredPointerKey#getTypeFilter()
		 */
		@Override
		public IPATypeFilter getTypeFilter() {
			return new SingleClassFilter(type);
		}

		@Override
		public boolean equals(Object obj) {
			// instanceof is OK because this class is final
			if (obj instanceof TypedPointerKey) {
				TypedPointerKey other = (TypedPointerKey) obj;
				return type.equals(other.type) && base.equals(other.base);
			} else {
				return false;
			}
		}

		@Override
		public int hashCode() {
			return 67931 * base.hashCode() + type.hashCode();
		}

		@Override
		public String toString() {
			return "{ " + base + " type: " + type + "}";
		}

		public PointerKey getBase() {
			return base;
		}
	}

	/**
	 * @param catchClasses Set of TypeReference
	 * @param klass an Exception Class
	 * @return true iff klass is a subclass of some element of the Set
	 * @throws IllegalArgumentException if catchClasses is null
	 */
	public static boolean catches(Set<IClass> catchClasses, IClass klass, IClassHierarchy cha) {
		if (catchClasses == null) {
			throw new IllegalArgumentException("catchClasses is null");
		}
		// quick shortcut
		if (catchClasses.size() == 1) {
			IClass c = catchClasses.iterator().next();
			if (c != null && c.getReference().equals(TypeReference.JavaLangThread)) {
				return true;
			}
		}
		for (IClass c : catchClasses) {
			if (c != null && cha.isAssignableFrom(c, klass)) {
				return true;
			}
		}
		return false;
	}

	public static boolean representsNullType(InstanceKey key) throws IllegalArgumentException {
		if (key == null) {
			throw new IllegalArgumentException("key == null");
		}
		IClass cls = key.getConcreteType();
		Language L = cls.getClassLoader().getLanguage();
		return L.isNullType(cls.getReference());
	}

	/**
	 * The FilterOperator is a filtered set-union. i.e. the LHS is `unioned' with the RHS, but filtered by the set associated with
	 * this operator instance. The filter is the set of InstanceKeys corresponding to the target type of this cast. This is still
	 * monotonic.
	 *
	 * LHS U= (RHS n k)
	 *
	 *
	 * Unary op: <lhs>:= Cast_k( <rhs>)
	 *
	 * (Again, technically a binary op -- see note for Assign)
	 *
	 * TODO: these need to be canonicalized.
	 *
	 */
	public class IPAFilterOperator extends IPAUnaryOperator<IPAPointsToSetVariable> implements IPointerOperator {

		protected IPAFilterOperator() {
		}

		/*
		 * @see com.ibm.wala.dataflow.UnaryOperator#evaluate(com.ibm.wala.dataflow.IVariable, com.ibm.wala.dataflow.IVariable)
		 */
		@Override
		public byte evaluate(IPAPointsToSetVariable lhs, IPAPointsToSetVariable rhs) {

			IPAFilteredPointerKey pk = (IPAFilteredPointerKey) lhs.getPointerKey();

			if (DEBUG_FILTER) {
				String S = "EVAL Filter " + lhs.getPointerKey() + " " + rhs.getPointerKey();
				S += "\nEVAL      " + lhs + " " + rhs;
				System.err.println(S);
			}
			if (rhs.size() == 0) {
				return NOT_CHANGED;
			}

			boolean changed = false;
			IPAFilteredPointerKey.IPATypeFilter filter = pk.getTypeFilter();
			changed = filter.addFiltered(system, lhs, rhs);

			if (DEBUG_FILTER) {
				System.err.println("RESULT " + lhs + (changed ? " (changed)" : ""));
			}

			return changed ? CHANGED : NOT_CHANGED;
		}

		/**
		 * bz: for sequential
		 * @param lhs
		 * @param set
		 * @return
		 */
		@Override
		public byte evaluateDel(IPAPointsToSetVariable lhs, IPAPointsToSetVariable rhs) {
			IPAFilteredPointerKey pk = (IPAFilteredPointerKey) lhs.getPointerKey();

			if (DEBUG_FILTER) {
				String S = "DEL EVAL Filter " + lhs.getPointerKey() + " " + rhs.getPointerKey();
				S += "\nEVAL      " + lhs + " " + rhs;
				System.err.println(S);

			}
			if(rhs.getValue() == null){
				return NOT_CHANGED;
			}
			if (rhs.size() == 0) {
				return NOT_CHANGED;
			}

			boolean changed = false;
			IPAFilteredPointerKey.IPATypeFilter filter = pk.getTypeFilter();
			if(system.getFirstDel()){
				changed = filter.delFiltered(system, lhs, rhs);
			}else{//gonna change to other place
				changed = filter.delFiltered(system, lhs, rhs.getChange());
			}
			return changed ? CHANGED : NOT_CHANGED;
		}

		/**
		 * bz: for parallel
		 * @param lhs
		 * @param set
		 * @return
		 */
		public byte evaluateDel(IPAPointsToSetVariable lhs, MutableSharedBitVectorIntSet set) {
			IPAFilteredPointerKey pk = (IPAFilteredPointerKey) lhs.getPointerKey();

			if(set == null){
				return NOT_CHANGED;
			}
			if (set.size() == 0) {
				return NOT_CHANGED;
			}

			boolean changed = false;
			IPAFilteredPointerKey.IPATypeFilter filter = pk.getTypeFilter();
			changed = filter.delFiltered(system, lhs, set);
			return changed ? CHANGED : NOT_CHANGED;
		}


		/*
		 * @see com.ibm.wala.ipa.callgraph.propagation.IPointerOperator#isComplex()
		 */
		@Override
		public boolean isComplex() {
			return false;
		}

		@Override
		public String toString() {
			return "Filter";
		}

		@Override
		public boolean equals(Object obj) {
			// these objects are canonicalized for the duration of a solve
			return this == obj;
		}

		@Override
		public int hashCode() {
			return 88651;
		}

	}

	@Override
	public IClassHierarchy getClassHierarchy() {
		return cha;
	}

	public AnalysisOptions getOptions() {
		return options;
	}

	public IClass getJavaLangObject() {
		return JAVA_LANG_OBJECT;
	}

	public IPAExplicitCallGraph getCallGraph() {
		return callGraph;
	}

	/**
	 * Subclasses must register the context interpreter before building a call graph.
	 */
	public void setContextInterpreter(SSAContextInterpreter interpreter) {
		contextInterpreter = interpreter;
		callGraph.setInterpreter(interpreter);
	}

	/*
	 * @see com.ibm.detox.ipa.callgraph.CallGraphBuilder#getPointerAnalysis()
	 */
	@Override
	public PointerAnalysis<InstanceKey> getPointerAnalysis() {
		return system.extractPointerAnalysis(this);
	}

	public PointerKeyFactory getPointerKeyFactory() {
		return pointerKeyFactory;
	}

	/** BEGIN Custom change: setter for pointerkey factory */
	public void setPointerKeyFactory(IPAPointerKeyFactory pkFact) {
		pointerKeyFactory = pkFact;
	}

	/** END Custom change: setter for pointerkey factory */
	public RTAContextInterpreter getContextInterpreter() {
		return contextInterpreter;
	}

	/**
	 * @param caller the caller node
	 * @param iKey an abstraction of the receiver of the call (or null if not applicable)
	 * @return the CGNode to which this particular call should dispatch.
	 */
	protected CGNode getTargetForCall(CGNode caller, CallSiteReference site, IClass recv, InstanceKey iKey[]) {
		IMethod targetMethod = options.getMethodTargetSelector().getCalleeTarget(caller, site, recv);

		// this most likely indicates an exclusion at work; the target selector
		// should have issued a warning
		if (targetMethod == null || targetMethod.isAbstract()) {
			return null;
		}
		Context targetContext = contextSelector.getCalleeTarget(caller, site, targetMethod, iKey);

		if (targetContext instanceof IllegalArgumentExceptionContext) {
			return null;
		}
		try {
			return getCallGraph().findOrCreateNode(targetMethod, targetContext);
		} catch (CancelException e) {
			return null;
		}
	}

	/**
	 * @return the context selector for this call graph builder
	 */
	public ContextSelector getContextSelector() {
		return contextSelector;
	}

	public void setContextSelector(ContextSelector selector) {
		contextSelector = selector;
	}

	public InstanceKeyFactory getInstanceKeys() {
		return instanceKeyFactory;
	}

	public void setInstanceKeys(InstanceKeyFactory keys) {
		this.instanceKeyFactory = keys;
	}

	/**
	 * @return the InstanceKey that acts as a representative for the class of objects that includes objects allocated at the given new
	 *         instruction in the given node
	 */
	public InstanceKey getInstanceKeyForAllocation(CGNode node, NewSiteReference allocation) {
		return instanceKeyFactory.getInstanceKeyForAllocation(node, allocation);
	}

	/**
	 * @param dim the dimension of the array whose instance we would like to model. dim == 0 represents the first dimension, e.g., the
	 *          [Object; instances in [[Object; e.g., the [[Object; instances in [[[Object; dim == 1 represents the second dimension,
	 *          e.g., the [Object instances in [[[Object;
	 * @return the InstanceKey that acts as a representative for the class of array contents objects that includes objects allocated
	 *         at the given new instruction in the given node
	 */
	public InstanceKey getInstanceKeyForMultiNewArray(CGNode node, NewSiteReference allocation, int dim) {
		return instanceKeyFactory.getInstanceKeyForMultiNewArray(node, allocation, dim);
	}

	public <T> InstanceKey getInstanceKeyForConstant(TypeReference type, T S) {
		return instanceKeyFactory.getInstanceKeyForConstant(type, S);
	}

	public InstanceKey getInstanceKeyForMetadataObject(Object obj, TypeReference objType) {
		return instanceKeyFactory.getInstanceKeyForMetadataObject(obj, objType);
	}

	public boolean haveAlreadyVisited(CGNode node) {
		return alreadyVisited.contains(node);
	}

	protected void markAlreadyVisited(CGNode node) {
		alreadyVisited.add(node);
	}

	/**
	 * record that we've discovered a node
	 */
	public void markDiscovered(CGNode node) {
		discoveredNodes.add(node);
	}

	protected void markChanged(CGNode node) {
		alreadyVisited.remove(node);
		discoveredNodes.add(node);
	}

	protected boolean wasChanged(CGNode node) {
		return discoveredNodes.contains(node) && !alreadyVisited.contains(node);
	}

	/**
	 * Binary op: <dummy>:= ArrayLoad( &lt;arrayref>) Side effect: Creates new equations.
	 */
	public final class ArrayLoadOperator extends IPAUnarySideEffect implements IPointerOperator {
		protected final MutableIntSet priorInstances = rememberGetPutHistory ? IntSetUtil.make() : null;

		@Override
		public String toString() {
			return "ArrayLoad";
		}

		public ArrayLoadOperator(IPAPointsToSetVariable def) {
			super(def);
			system.registerFixedSet(def, this);
		}

		@Override
		public byte evaluate(IPAPointsToSetVariable rhs) {
			if (DEBUG_ARRAY_LOAD) {
				IPAPointsToSetVariable def = getFixedSet();
				String S = "EVAL ArrayLoad " + rhs.getPointerKey() + " " + def.getPointerKey();
				System.err.println(S);
				System.err.println("EVAL ArrayLoad " + def + " " + rhs);
				if (priorInstances != null) {
					System.err.println("prior instances: " + priorInstances + " " + priorInstances.getClass());
				}
			}

			if (rhs.size() == 0) {
				return NOT_CHANGED;
			}

			IPAPointsToSetVariable def = getFixedSet();
			final PointerKey dVal = def.getPointerKey();

			final MutableBoolean sideEffect = new MutableBoolean();
			IntSetAction action = new IntSetAction() {
				@Override
				public void act(int i) {
					InstanceKey I = system.getInstanceKey(i);
					if (!I.getConcreteType().isArrayClass()) {
						return;
					}
					TypeReference C = I.getConcreteType().getReference().getArrayElementType();
					if (C.isPrimitiveType()) {
						return;
					}
					PointerKey p = getPointerKeyForArrayContents(I);
					if (p == null) {
						return;
					}

					if (DEBUG_ARRAY_LOAD) {
						System.err.println("ArrayLoad add assign: " + dVal + " " + p);
					}
					sideEffect.b |= system.newFieldRead(dVal, assignOperator, p);
				}
			};
			if (priorInstances != null) {
				rhs.getValue().foreachExcluding(priorInstances, action);
				priorInstances.addAll(rhs.getValue());
			} else {
				rhs.getValue().foreach(action);
			}
			byte sideEffectMask = sideEffect.b ? (byte) SIDE_EFFECT_MASK : 0;
			return (byte) (NOT_CHANGED | sideEffectMask);
		}

		/**
		 * bz:
		 */
		@Override
		public byte evaluateDel(IPAPointsToSetVariable rhs) {
			if (DEBUG_ARRAY_LOAD) {
				IPAPointsToSetVariable def = getFixedSet();
				String S = "DEL EVAL ArrayLoad " + rhs.getPointerKey() + " " + def.getPointerKey();
				System.err.println(S);
				System.err.println("DEl EVAL ArrayLoad " + def + " " + rhs);
				if (priorInstances != null) {
					System.err.println("prior instances: " + priorInstances + " " + priorInstances.getClass());
				}
			}

			if (rhs.size() == 0) {
				return NOT_CHANGED;
			}

			IPAPointsToSetVariable def = getFixedSet();
			final PointerKey dVal = def.getPointerKey();

			final MutableBoolean sideEffect_del = new MutableBoolean();
			final MutableIntSet delset = IntSetUtil.getDefaultIntSetFactory().make();
			final ArrayList<IPAPointsToSetVariable> rhss = new ArrayList<>();
			IntSetAction action = new IntSetAction() {
				@Override
				public void act(int i) {
					InstanceKey I = system.getInstanceKey(i);
					if (!I.getConcreteType().isArrayClass()) {
						return;
					}
					TypeReference C = I.getConcreteType().getReference().getArrayElementType();
					if (C.isPrimitiveType()) {
						return;
					}
					PointerKey p = getPointerKeyForArrayContents(I);
					if (p == null) {
						return;
					}

					if (DEBUG_ARRAY_LOAD) {
						System.err.println("ArrayLoad del assign: " + dVal + " " + p);
					}
					IPAPointsToSetVariable ptv = system.findOrCreatePointsToSet(p);
					if(ptv.getValue() != null){
						delset.addAll(ptv.getValue());
						rhss.add(ptv);
					}
				}
			};
			if(system.getFirstDel()){
				rhs.getValue().foreach(action);
			}else{
				rhs.getChange().foreach(action);
			}
			if(rhss.size() != 0)
				sideEffect_del.b |= system.delConstraintHasMultiR(def, assignOperator, rhss, delset);
			priorInstances.foreach(action);
			priorInstances.clear();
			delset.clear();

			byte sideEffectMask = sideEffect_del.b ? (byte) SIDE_EFFECT_MASK : 0;
			return (byte) (NOT_CHANGED | sideEffectMask);
		}

		@Override
		public int hashCode() {
			return 9871 + super.hashCode();
		}

		@Override
		public boolean equals(Object o) {
			return super.equals(o);
		}

		@Override
		protected boolean isLoadOperator() {
			return true;
		}

		/*
		 * @see com.ibm.wala.ipa.callgraph.propagation.IPointerOperator#isComplex()
		 */
		@Override
		public boolean isComplex() {
			return true;
		}
	}

	/**
	 * Binary op: <dummy>:= ArrayStore( &lt;arrayref>) Side effect: Creates new equations.
	 */
	public final class ArrayStoreOperator extends IPAUnarySideEffect implements IPointerOperator {
		@Override
		public String toString() {
			return "ArrayStore";
		}

		public ArrayStoreOperator(IPAPointsToSetVariable val) {
			super(val);
			system.registerFixedSet(val, this);
		}

		@Override
		public byte evaluate(IPAPointsToSetVariable rhs) {
			if (DEBUG_ARRAY_STORE) {
				IPAPointsToSetVariable val = getFixedSet();
				String S = "EVAL ArrayStore " + rhs.getPointerKey() + " " + val.getPointerKey();
				System.err.println(S);
				System.err.println("EVAL ArrayStore " + rhs + " " + getFixedSet());
			}

			if (rhs.size() == 0) {
				return NOT_CHANGED;
			}

			IPAPointsToSetVariable val = getFixedSet();
			PointerKey pVal = val.getPointerKey();

			List<InstanceKey> instances = system.getInstances(rhs.getValue());
			boolean sideEffect = false;
			for (Iterator<InstanceKey> it = instances.iterator(); it.hasNext();) {
				InstanceKey I = it.next();
				if (!I.getConcreteType().isArrayClass()) {
					continue;
				}
				if (I instanceof ZeroLengthArrayInNode) {
					continue;
				}
				TypeReference C = I.getConcreteType().getReference().getArrayElementType();
				if (C.isPrimitiveType()) {
					continue;
				}
				IClass contents = getClassHierarchy().lookupClass(C);
				if (contents == null) {
					assert false : "null type for " + C + " " + I.getConcreteType();
				}
				PointerKey p = getPointerKeyForArrayContents(I);
				if (DEBUG_ARRAY_STORE) {
					System.err.println("ArrayStore add filtered-assign: " + p + " " + pVal);
				}

				// note that the following is idempotent
				if (isJavaLangObject(contents)) {
					sideEffect |= system.newFieldWrite(p, assignOperator, pVal);
				} else {
					sideEffect |= system.newFieldWrite(p, filterOperator, pVal);
				}
			}
			byte sideEffectMask = sideEffect ? (byte) SIDE_EFFECT_MASK : 0;
			return (byte) (NOT_CHANGED | sideEffectMask);
		}

		//bz
		@Override
		public byte evaluateDel(IPAPointsToSetVariable rhs) {
			if (DEBUG_ARRAY_STORE) {
				IPAPointsToSetVariable val = getFixedSet();
				String S = "DEL EVAL ArrayStore " + rhs.getPointerKey() + " " + val.getPointerKey();
				System.err.println(S);
				System.err.println("DEL EVAL ArrayStore " + rhs + " " + getFixedSet());
			}

			if (rhs.size() == 0) {
				return NOT_CHANGED;
			}
			IPAPointsToSetVariable val = getFixedSet();
			PointerKey pVal = val.getPointerKey();

			List<InstanceKey> instances = system.getInstances(rhs.getValue());
			boolean sideEffect_del = false;
			for (Iterator<InstanceKey> it = instances.iterator(); it.hasNext();) {
				InstanceKey I = it.next();
				if (!I.getConcreteType().isArrayClass()) {
					continue;
				}
				if (I instanceof ZeroLengthArrayInNode) {
					continue;
				}
				TypeReference C = I.getConcreteType().getReference().getArrayElementType();
				if (C.isPrimitiveType()) {
					continue;
				}
				IClass contents = getClassHierarchy().lookupClass(C);
				if (contents == null) {
					assert false : "null type for " + C + " " + I.getConcreteType();
				}
				PointerKey p = getPointerKeyForArrayContents(I);
				if (DEBUG_ARRAY_STORE) {
					System.err.println("ArrayStore del filtered-assign: " + p + " " + pVal);
				}

				// note that the following is idempotent
				if (isJavaLangObject(contents)) {
					sideEffect_del |= system.delConstraint(p, assignOperator, pVal);
				} else {
					sideEffect_del |= system.delConstraint(p, filterOperator, pVal);
				}
			}
			byte sideEffectMask = sideEffect_del ? (byte) SIDE_EFFECT_MASK : 0;
			return (byte) (NOT_CHANGED | sideEffectMask);
		}

		@Override
		public int hashCode() {
			return 9859 + super.hashCode();
		}

		@Override
		public boolean isComplex() {
			return true;
		}

		@Override
		public boolean equals(Object o) {
			return super.equals(o);
		}

		@Override
		protected boolean isLoadOperator() {
			return false;
		}
	}

	/**
	 * Binary op: <dummy>:= GetField( <ref>) Side effect: Creates new equations.
	 */
	public class GetFieldOperator extends IPAUnarySideEffect implements IPointerOperator {
		private final IField field;

		protected final MutableIntSet priorInstances = rememberGetPutHistory ? IntSetUtil.make() : null;

		public GetFieldOperator(IField field, IPAPointsToSetVariable def) {
			super(def);
			this.field = field;
			system.registerFixedSet(def, this);
		}

		@Override
		public String toString() {
			return "GetField " + getField() + "," + getFixedSet().getPointerKey();
		}

		@Override
		public byte evaluate(IPAPointsToSetVariable rhs) {
			if (DEBUG_GET) {
				String S = "EVAL GetField " + getField() + " " + getFixedSet().getPointerKey() + " " + rhs.getPointerKey() + getFixedSet()
				+ " " + rhs;
				System.err.println(S);
			}

			IPAPointsToSetVariable ref = rhs;
			if (ref.size() == 0) {
				return NOT_CHANGED;
			}
			IPAPointsToSetVariable def = getFixedSet();
			final PointerKey dVal = def.getPointerKey();

			IntSet value = filterInstances(ref.getValue());
			if (DEBUG_GET) {
				System.err.println("filtered value: " + value + " " + value.getClass());
				if (priorInstances != null) {
					System.err.println("prior instances: " + priorInstances + " " + priorInstances.getClass());
				}
			}
			final MutableBoolean sideEffect = new MutableBoolean();
			IntSetAction action = new IntSetAction() {
				@Override
				public void act(int i) {
					InstanceKey I = system.getInstanceKey(i);
					if (!representsNullType(I)) {
						PointerKey p = getPointerKeyForInstanceField(I, getField());
						if (p != null) {
							if (DEBUG_GET) {
								String S = "Getfield add constraint " + dVal + " " + p;
								System.err.println(S);
							}
							sideEffect.b |= system.newFieldRead(dVal, assignOperator, p);
						}
					}
				}
			};

			final MutableIntSet targets = IntSetUtil.getDefaultIntSetFactory().make();
			final ArrayList<IPAPointsToSetVariable> rhss = new ArrayList<>();
			IntSetAction action2 = new IntSetAction() {
				@Override
				public void act(int i) {
					InstanceKey I = system.getInstanceKey(i);
					if (!representsNullType(I)) {
						//--- this for getField is the GetFieldOperator
						PointerKey p = getPointerKeyForInstanceField(I, getField());
						if (p != null) {
							if (DEBUG_GET) {
								String S = "Getfield del constraint " + dVal + " " + p;
								System.err.println(S);
							}
							IPAPointsToSetVariable ptv = system.findOrCreatePointsToSet(p);
							if(ptv.getValue() != null){
								targets.addAll(ptv.getValue());
							}
							rhss.add(ptv);
						}
					}
				}
			};

			if(system.isChange){
				if(value.size() < 10){
					if (priorInstances != null) {
						value.foreachExcluding(priorInstances, action);
						priorInstances.addAll(value);
					} else {
						value.foreach(action);
					}
				}else{
					if (priorInstances != null) {
						value.foreachExcluding(priorInstances, action2);
						priorInstances.addAll(value);
					} else {
						value.foreach(action2);
					}
					if(rhss.size() > 0)
						sideEffect.b |= system.addConstraintHasMultiR(def, assignOperator, rhss, targets);
				}
			}else{
				if (priorInstances != null) {
					value.foreachExcluding(priorInstances, action);
					priorInstances.addAll(value);
				} else {
					value.foreach(action);
				}
			}

			byte sideEffectMask = sideEffect.b ? (byte) SIDE_EFFECT_MASK : 0;
			return (byte) (NOT_CHANGED | sideEffectMask);
		}

		//bz
		@Override
		public byte evaluateDel(IPAPointsToSetVariable rhs) {
			if (DEBUG_GET) {
				String S = "DEL EVAL GetField " + getField() + " " + getFixedSet().getPointerKey() + " " + rhs.getPointerKey() + getFixedSet()
				+ " " + rhs;
				System.err.println(S);
			}

			IPAPointsToSetVariable ref = rhs;
			if (ref.size() == 0) {
				return NOT_CHANGED;
			}
			IPAPointsToSetVariable def = getFixedSet();
			final PointerKey dVal = def.getPointerKey();
			//~~~ did not implement filter part
			IntSet value = filterInstances(ref.getValue());
			if (DEBUG_GET) {
				System.err.println("filtered value: " + value + " " + value.getClass());
				if (priorInstances != null) {
					System.err.println("prior instances: " + priorInstances + " " + priorInstances.getClass());
				}
			}
			final MutableBoolean sideEffect_del = new MutableBoolean();
			final MutableIntSet delset = IntSetUtil.getDefaultIntSetFactory().make();
			final ArrayList<IPAPointsToSetVariable> rhss = new ArrayList<>();
			IntSetAction action = new IntSetAction() {
				@Override
				public void act(int i) {
					InstanceKey I = system.getInstanceKey(i);
					if (!representsNullType(I)) {
						//--- this for getField is the GetFieldOperator
						PointerKey p = getPointerKeyForInstanceField(I, getField());

						if (p != null) {
							if (DEBUG_GET) {
								String S = "Getfield del constraint " + dVal + " " + p;
								System.err.println(S);
							}
							//              sideEffect_del.b |= system.delConstraint(dVal, assignOperator, p);
							IPAPointsToSetVariable ptv = system.findOrCreatePointsToSet(p);
							if(ptv.getValue() != null){
								delset.addAll(ptv.getValue());
							}
							rhss.add(ptv);
						}
					}
				}
			};
			//*** always do it for all instance
			if(system.getFirstDel()){
				value.foreach(action);
				priorInstances.foreach(action);
			}else{
				rhs.getChange().foreach(action);
			}
			if(rhss.size() != 0)
				sideEffect_del.b |= system.delConstraintHasMultiR(def, assignOperator, rhss, delset);
			//--- remove all priorInstance
			priorInstances.clear();

			byte sideEffectMask = sideEffect_del.b ? (byte) SIDE_EFFECT_MASK : 0;
			return (byte) (NOT_CHANGED | sideEffectMask);
		}

		/**
		 * Subclasses can override as needed
		 */
		protected IntSet filterInstances(IntSet value) {
			return value;
		}

		@Override
		public int hashCode() {
			return 9857 * getField().hashCode() + getFixedSet().hashCode();
		}

		@Override
		public boolean equals(Object o) {
			if (o instanceof GetFieldOperator) {
				GetFieldOperator other = (GetFieldOperator) o;
				return getField().equals(other.getField()) && getFixedSet().equals(other.getFixedSet());
			} else {
				return false;
			}
		}

		protected IField getField() {
			return field;
		}

		@Override
		protected boolean isLoadOperator() {
			return true;
		}

		/*
		 * @see com.ibm.wala.ipa.callgraph.propagation.IPointerOperator#isComplex()
		 */
		@Override
		public boolean isComplex() {
			return true;
		}
	}

	/**
	 * Operator that represents a putfield
	 */
	public class PutFieldOperator extends IPAUnarySideEffect implements IPointerOperator {
		private final IField field;

		protected final MutableIntSet priorInstances = rememberGetPutHistory ? IntSetUtil.make() : null;

		@Override
		public String toString() {
			return "PutField" + getField();
		}

		public PutFieldOperator(IField field, IPAPointsToSetVariable val) {
			super(val);
			this.field = field;
			system.registerFixedSet(val, this);
		}

		/*
		 * @see com.ibm.wala.ipa.callgraph.propagation.IPointerOperator#isComplex()
		 */
		@Override
		public boolean isComplex() {
			return true;
		}

		@Override
		public byte evaluate(IPAPointsToSetVariable rhs) {
			if (DEBUG_PUT) {
				String S = "EVAL PutField " + getField() + " " + (getFixedSet()).getPointerKey() + " " + rhs.getPointerKey()
				+ getFixedSet() + " " + rhs;
				System.err.println(S);
			}

			if (rhs.size() == 0) {
				return NOT_CHANGED;
			}

			IPAPointsToSetVariable val = getFixedSet();
			final PointerKey pVal = val.getPointerKey();
			IntSet value = rhs.getValue();
			value = filterInstances(value);
			final IPAUnaryOperator<IPAPointsToSetVariable> assign = getPutAssignmentOperator();
			if (assign == null) {
				Assertions.UNREACHABLE();
			}
			final MutableBoolean sideEffect = new MutableBoolean();
			IntSetAction action = new IntSetAction() {
				@Override
				public void act(int i) {
					InstanceKey I = system.getInstanceKey(i);
					if (!representsNullType(I)) {
						if (DEBUG_PUT) {
							String S = "Putfield consider instance " + I;
							System.err.println(S);
						}
						PointerKey p = getPointerKeyForInstanceField(I, getField());
						if (p != null) {
							if (DEBUG_PUT) {
								String S = "Putfield add constraint " + p + " " + pVal;
								System.err.println(S);
							}
							sideEffect.b |= system.newFieldWrite(p, assign, pVal);
						}
					}
				}
			};

			final ArrayList<IPAPointsToSetVariable> lhss = new ArrayList<>();
			IntSetAction action2 = new IntSetAction() {
				@Override
				public void act(int i) {
					InstanceKey I = system.getInstanceKey(i);
					if (!representsNullType(I)) {
						PointerKey p = getPointerKeyForInstanceField(I, getField());
						if(p != null){
							IPAPointsToSetVariable pptv = system.findOrCreatePointsToSet(p);
//							MutableIntSet set = pptv.getValue();
//							if(set != null)
							lhss.add(pptv);
						}
					}
				}
			};

			if(system.isChange){
				if(value.size() < 10){
					if (priorInstances != null) {
						value.foreachExcluding(priorInstances, action);
						priorInstances.addAll(value);
					} else {
						value.foreach(action);
					}
				}else{
					if (priorInstances != null) {
						value.foreachExcluding(priorInstances, action2);
						priorInstances.addAll(value);
					} else {
						value.foreach(action2);
					}
					MutableIntSet targets = IntSetUtil.getDefaultIntSetFactory().make();
					if(val.getValue() != null){
						targets.addAll(val.getValue());
					}
					system.addConstraintHasMultiL(lhss, assignOperator, val, targets);
				}
			}else{
				if (priorInstances != null) {
					value.foreachExcluding(priorInstances, action);
					priorInstances.addAll(value);
				} else {
					value.foreach(action);
				}
			}
			byte sideEffectMask = sideEffect.b ? (byte) SIDE_EFFECT_MASK : 0;
			return (byte) (NOT_CHANGED | sideEffectMask);
		}

		/**
		 * bz:
		 */
		@Override
		public byte evaluateDel(IPAPointsToSetVariable rhs) {
			if (DEBUG_PUT) {
				String S = "DEL EVAL PutField " + getField() + " " + (getFixedSet()).getPointerKey() + " " + rhs.getPointerKey()
				+ getFixedSet() + " " + rhs;
				System.err.println(S);
			}

			if (rhs.size() == 0) {
				return NOT_CHANGED;
			}

			final IPAPointsToSetVariable val = getFixedSet();
			final PointerKey pVal = val.getPointerKey();
			final IPAUnaryOperator<IPAPointsToSetVariable> assign = getPutAssignmentOperator();
			if (assign == null) {
				Assertions.UNREACHABLE();
			}
			final MutableBoolean sideEffect_del = new MutableBoolean();
			IntSetAction action = new IntSetAction() {
				@Override
				public void act(int i) {
					InstanceKey I = system.getInstanceKey(i);
					if (!representsNullType(I)) {
						if (DEBUG_PUT) {
							String S = "Putfield consider instance " + I;
							System.err.println(S);
						}
						PointerKey p = getPointerKeyForInstanceField(I, getField());
						if (p != null) {
							if (DEBUG_PUT) {
								String S = "Putfield del constraint " + p + " " + pVal;
								System.err.println(S);
							}
							sideEffect_del.b |= system.delConstraint(p, assign, pVal);//bz: optimize
						}
					}
				}
			};

			final ArrayList<IPAPointsToSetVariable> lhss = new ArrayList<>();
			IntSetAction action2 = new IntSetAction() {
				@Override
				public void act(int i) {
					InstanceKey I = system.getInstanceKey(i);
					if (!representsNullType(I)) {
						PointerKey p = getPointerKeyForInstanceField(I, getField());
						if(p != null){
							IPAPointsToSetVariable pptv = system.findOrCreatePointsToSet(p);
//							MutableIntSet set = pptv.getValue();
//							if(set != null)
								lhss.add(pptv);
						}
					}
				}
			};

			IntSet value = null;
			if(system.getFirstDel()){
				value = rhs.getValue();
			}else{
				value = rhs.getChange();
			}
			value = filterInstances(value);
			if(value.size() < 10)
				value.foreach(action);
			else{
				value.foreach(action2);
				MutableIntSet targets = IntSetUtil.getDefaultIntSetFactory().make();
				if(val.getValue() != null){
					targets.addAll(val.getValue());
				}
				system.delConstraintHasMultiL(lhss, assignOperator, val, targets);
			}
			//always do this for all instances
			priorInstances.foreach(action);
			priorInstances.clear();

			byte sideEffectMask = sideEffect_del.b ? (byte) SIDE_EFFECT_MASK : 0;
			return (byte) (NOT_CHANGED | sideEffectMask);
		}

		/**
		 * Subclasses can override as needed
		 */
		protected IntSet filterInstances(IntSet value) {
			return value;
		}

		@Override
		public int hashCode() {
			return 9857 * getField().hashCode() + getFixedSet().hashCode();
		}

		@Override
		public boolean equals(Object o) {
			if (o != null && o.getClass().equals(getClass())) {
				PutFieldOperator other = (PutFieldOperator) o;
				return getField().equals(other.getField()) && getFixedSet().equals(other.getFixedSet());
			} else {
				return false;
			}
		}

		/**
		 * subclasses (e.g. XTA) can override this to enforce a filtered assignment. returns null if there's a problem.
		 */
		public IPAUnaryOperator<IPAPointsToSetVariable> getPutAssignmentOperator() {
			return assignOperator;
		}

		/**
		 * @return Returns the field.
		 */
		protected IField getField() {
			return field;
		}

		@Override
		protected boolean isLoadOperator() {
			return false;
		}
	}

	/**
	 * Update the points-to-set for a field to include a particular instance key.
	 */
	public final class InstancePutFieldOperator extends IPAUnaryOperator<IPAPointsToSetVariable> implements IPointerOperator {
		final private IField field;

		final private InstanceKey instance;

		protected final MutableIntSet priorInstances = rememberGetPutHistory ? IntSetUtil.make() : null;

		@Override
		public String toString() {
			return "InstancePutField" + field;
		}

		public InstancePutFieldOperator(IField field, InstanceKey instance) {
			this.field = field;
			this.instance = instance;
		}

		/**
		 * Simply add the instance to each relevant points-to set.
		 */
		@Override
		public byte evaluate(IPAPointsToSetVariable dummyLHS, IPAPointsToSetVariable var) {
			IPAPointsToSetVariable ref = var;
			if (ref.size() == 0) {
				return NOT_CHANGED;
			}
			IntSet value = ref.getValue();
			final MutableBoolean sideEffect = new MutableBoolean();
			IntSetAction action = new IntSetAction() {
				@Override
				public void act(int i) {
					InstanceKey I = system.getInstanceKey(i);
					if (!representsNullType(I)) {
						PointerKey p = getPointerKeyForInstanceField(I, field);
						if (p != null) {
							sideEffect.b |= system.newConstraint(p, instance);
						}
					}
				}
			};
			if (priorInstances != null) {
				value.foreachExcluding(priorInstances, action);
				priorInstances.addAll(value);
			} else {
				value.foreach(action);
			}
			byte sideEffectMask = sideEffect.b ? (byte) SIDE_EFFECT_MASK : 0;
			return (byte) (NOT_CHANGED | sideEffectMask);
		}

		/**bz
		 */
		@Override
		public byte evaluateDel(IPAPointsToSetVariable dummyLHS, IPAPointsToSetVariable var) {
			IPAPointsToSetVariable ref = var;
			if (ref.size() == 0) {
				return NOT_CHANGED;
			}
			IntSet value = null;
			if(system.getFirstDel()){
				value = ref.getValue();
			}else{
				value = ref.getChange();
			}
			final MutableBoolean sideEffect_del = new MutableBoolean();
			final ArrayList<IPAPointsToSetVariable> lhss = new ArrayList<>();
			IntSetAction action = new IntSetAction() {
				@Override
				public void act(int i) {
					InstanceKey I = system.getInstanceKey(i);
					if (!representsNullType(I)) {
						PointerKey p = getPointerKeyForInstanceField(I, field);
						if (p != null) {
							//              sideEffect_del.b |= system.delConstraint(p, instance);
							IPAPointsToSetVariable lhs = system.findOrCreatePointsToSet(p);
							if(lhs != null)
								lhss.add(lhs);
						}
					}
				}
			};

			value.foreach(action);
			MutableIntSet delset = IntSetUtil.make();
			delset.add(system.findOrCreateIndexForInstanceKey(instance));
			system.delConstraintHasMultiInstanceL(lhss, delset, ref);
			priorInstances.foreach(action);
			priorInstances.clear();
			byte sideEffectMask = sideEffect_del.b ? (byte) SIDE_EFFECT_MASK : 0;
			return (byte) (NOT_CHANGED | sideEffectMask);
		}

		@Override
		public int hashCode() {
			return field.hashCode() + 9839 * instance.hashCode();
		}

		@Override
		public boolean equals(Object o) {
			if (o instanceof InstancePutFieldOperator) {
				InstancePutFieldOperator other = (InstancePutFieldOperator) o;
				return field.equals(other.field) && instance.equals(other.instance);
			} else {
				return false;
			}
		}

		/*
		 * @see com.ibm.wala.ipa.callgraph.propagation.IPointerOperator#isComplex()
		 */
		@Override
		public boolean isComplex() {
			return true;
		}
	}

	/**
	 * Update the points-to-set for an array contents to include a particular instance key.
	 */
	public final class InstanceArrayStoreOperator extends IPAUnaryOperator<IPAPointsToSetVariable> implements IPointerOperator {
		final private InstanceKey instance;

		protected final MutableIntSet priorInstances = rememberGetPutHistory ? IntSetUtil.make() : null;

		@Override
		public String toString() {
			return "InstanceArrayStore ";
		}

		public InstanceArrayStoreOperator(InstanceKey instance) {
			this.instance = instance;
		}

		/**
		 * Simply add the instance to each relevant points-to set.
		 */
		@Override
		public byte evaluate(IPAPointsToSetVariable dummyLHS, IPAPointsToSetVariable var) {
			IPAPointsToSetVariable arrayref = var;
			if (arrayref.size() == 0) {
				return NOT_CHANGED;
			}
			IntSet value = arrayref.getValue();
			final MutableBoolean sideEffect = new MutableBoolean();
			IntSetAction action = new IntSetAction() {
				@Override
				public void act(int i) {
					InstanceKey I = system.getInstanceKey(i);
					if (!I.getConcreteType().isArrayClass()) {
						return;
					}
					if (I instanceof ZeroLengthArrayInNode) {
						return;
					}
					TypeReference C = I.getConcreteType().getReference().getArrayElementType();
					if (C.isPrimitiveType()) {
						return;
					}
					IClass contents = getClassHierarchy().lookupClass(C);
					if (contents == null) {
						assert false : "null type for " + C + " " + I.getConcreteType();
					}
					PointerKey p = getPointerKeyForArrayContents(I);
					if (contents.isInterface()) {
						if (getClassHierarchy().implementsInterface(instance.getConcreteType(), contents)) {
							sideEffect.b |= system.newConstraint(p, instance);
						}
					} else {
						if (getClassHierarchy().isSubclassOf(instance.getConcreteType(), contents)) {
							sideEffect.b |= system.newConstraint(p, instance);
						}
					}
				}
			};
			if (priorInstances != null) {
				value.foreachExcluding(priorInstances, action);
				priorInstances.addAll(value);
			} else {
				value.foreach(action);
			}
			byte sideEffectMask = sideEffect.b ? (byte) SIDE_EFFECT_MASK : 0;
			return (byte) (NOT_CHANGED | sideEffectMask);
		}

		/*bz*/
		@Override
		public byte evaluateDel(IPAPointsToSetVariable dummyLHS, IPAPointsToSetVariable var) {
			IPAPointsToSetVariable arrayref = var;
			if (arrayref.size() == 0) {
				return NOT_CHANGED;
			}
			IntSet value = null;
			if(system.getFirstDel()){
				value = arrayref.getValue();
			}else{
				value = arrayref.getChange();
			}
			final MutableBoolean sideEffect_del = new MutableBoolean();
			final ArrayList<IPAPointsToSetVariable> lhss = new ArrayList<>();
			IntSetAction action = new IntSetAction() {
				@Override
				public void act(int i) {
					InstanceKey I = system.getInstanceKey(i);
					if (!I.getConcreteType().isArrayClass()) {
						return;
					}
					if (I instanceof ZeroLengthArrayInNode) {
						return;
					}
					TypeReference C = I.getConcreteType().getReference().getArrayElementType();
					if (C.isPrimitiveType()) {
						return;
					}
					IClass contents = getClassHierarchy().lookupClass(C);
					if (contents == null) {
						assert false : "null type for " + C + " " + I.getConcreteType();
					}
					PointerKey p = getPointerKeyForArrayContents(I);
					if (contents.isInterface()) {
						if (getClassHierarchy().implementsInterface(instance.getConcreteType(), contents)) {
							//              sideEffect_del.b |= system.delConstraint(p, instance);
							lhss.add(system.findOrCreatePointsToSet(p));
						}
					} else {
						if (getClassHierarchy().isSubclassOf(instance.getConcreteType(), contents)) {
							//              sideEffect_del.b |= system.delConstraint(p, instance);
							lhss.add(system.findOrCreatePointsToSet(p));
						}
					}
				}
			};
			//*** in case of prior instances is not a subset of value
			priorInstances.foreach(action);
			value.foreach(action);
			MutableIntSet delset = IntSetUtil.make();
			delset.add(system.findOrCreateIndexForInstanceKey(instance));
			system.delConstraintHasMultiInstanceL(lhss, delset, arrayref);
			priorInstances.clear();
			byte sideEffectMask = sideEffect_del.b ? (byte) SIDE_EFFECT_MASK : 0;
			return (byte) (NOT_CHANGED | sideEffectMask);
		}

		@Override
		public int hashCode() {
			return 9839 * instance.hashCode();
		}

		@Override
		public boolean equals(Object o) {
			if (o instanceof InstanceArrayStoreOperator) {
				InstanceArrayStoreOperator other = (InstanceArrayStoreOperator) o;
				return instance.equals(other.instance);
			} else {
				return false;
			}
		}

		/*
		 * @see com.ibm.wala.ipa.callgraph.propagation.IPointerOperator#isComplex()
		 */
		@Override
		public boolean isComplex() {
			return true;
		}
	}

	protected MutableIntSet getMutableInstanceKeysForClass(IClass klass) {
		return system.cloneInstanceKeysForClass(klass);
	}

	protected IntSet getInstanceKeysForClass(IClass klass) {
		return system.getInstanceKeysForClass(klass);
	}

	/**
	 * @param klass a class
	 * @return an int set which represents the subset of S that correspond to subtypes of klass
	 */
	@SuppressWarnings("unused")
	protected IntSet filterForClass(IntSet S, IClass klass) {
		MutableIntSet filter = null;
		if (klass.getReference().equals(TypeReference.JavaLangObject)) {
			return S;
		} else {
			filter = getMutableInstanceKeysForClass(klass);

			boolean debug = false;
			if (DEBUG_FILTER) {
				String s = "klass     " + klass;
				System.err.println(s);
				System.err.println("initial filter    " + filter);
			}
			filter.intersectWith(S);

			if (DEBUG_FILTER && debug) {
				System.err.println("final filter    " + filter);
			}
		}
		return filter;
	}

	/**
	 * pi should never be deleted...
	 * @author Bozhen
	 */
	protected class InverseFilterOperator extends IPAFilterOperator {
		public InverseFilterOperator() {
			super();
		}

		@Override
		public String toString() {
			return "InverseFilter";
		}

		/*
		 * @see com.ibm.wala.ipa.callgraph.propagation.IPointerOperator#isComplex()
		 */
		@Override
		public boolean isComplex() {
			return false;
		}

		/*
		 * simply check if rhs contains a malleable.
		 *
		 * @see com.ibm.wala.dataflow.UnaryOperator#evaluate(com.ibm.wala.dataflow.IVariable, com.ibm.wala.dataflow.IVariable)
		 */
		@Override
		public byte evaluate(IPAPointsToSetVariable lhs, IPAPointsToSetVariable rhs) {

			IPAFilteredPointerKey pk = (IPAFilteredPointerKey) lhs.getPointerKey();
			IPAFilteredPointerKey.IPATypeFilter filter = pk.getTypeFilter();

			boolean debug = false;
			if (DEBUG_FILTER) {
				String S = "EVAL InverseFilter/" + filter + " " + lhs.getPointerKey() + " " + rhs.getPointerKey();
				S += "\nEVAL      " + lhs + " " + rhs;
				System.err.println(S);
			}
			if (rhs.size() == 0) {
				return NOT_CHANGED;
			}

			boolean changed = filter.addInverseFiltered(system, lhs, rhs);

			if (DEBUG_FILTER) {
				if (debug) {
					System.err.println("RESULT " + lhs + (changed ? " (changed)" : ""));
				}
			}
			return changed ? CHANGED : NOT_CHANGED;
		}
	}

	protected IPointsToSolver getSolver() {
		return solver;
	}

	/**
	 * Add constraints when the interpretation of a node changes (e.g. reflection)
	 * @param monitor
	 * @throws CancelException
	 */
	public void addConstraintsFromChangedNode(CGNode node, IProgressMonitor monitor) throws CancelException {
		unconditionallyAddConstraintsFromNode(node, monitor);
	}

	protected abstract boolean unconditionallyAddConstraintsFromNode(CGNode node, IProgressMonitor monitor) throws CancelException;

	protected static class MutableBoolean {
		// a horrendous hack since we don't have closures
		boolean b = false;
	}

	@Override
	public IAnalysisCacheView getAnalysisCache() {
		return analysisCache;
	}

	/**bz
	 * flag for deleting constraints
	 * @param delete
	 */
	public abstract void setDelete(boolean delete);

	/**bz: to compute the average time of incremental analysis
	 */
	public static long total_add = 0;
	public static long total_del = 0;
	public static int total_inst = 0;
	/**
	 * bz: for incremental pta check
	 * @param var_pts_map
	 * @param b
	 * @param n
	 * @return
	 */
	public boolean testChange(CGNode node, HashMap<PointerKey, MutableIntSet> var_pts_map, boolean measurePerformance) {
		boolean correct = true;
		system.setChange(true);
		IR ir = node.getIR();
		if(ir==null)
			return correct;

		DefUse du = new DefUse(ir);
		ConstraintVisitor v = ((IPASSAPropagationCallGraphBuilder)this).makeVisitor(node);
		v.setIR(ir);
		v.setDefUse(du);

		ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg = ir.getControlFlowGraph();
		SSAInstruction[] insts = ir.getInstructions();
		int size = insts.length;

		for(int i=0; i<size; i++){
			SSAInstruction inst = insts[i];

			if(inst==null)
				continue;//skip null

//			if(!inst.toString().contains("59 = invokevirtual < Application, Lorg/eclipse/osgi/baseadaptor/BaseData, getBundleFile()Lorg/eclipse/osgi/baseadaptor/bundlefile/BundleFile; > 54 @231 exception:58"))
//				continue;

			total_inst++;
			ISSABasicBlock bb = cfg.getBlockForInstruction(inst.iindex);
			//delete
			try{
				System.out.println("... Deleting SSAInstruction:      "+ inst.toString());
				this.setDelete(true);
				system.setFirstDel(true);
				v.setBasicBlock(bb);

				long start_delete = System.currentTimeMillis();
				inst.visit(v);
				system.setFirstDel(false);
				do{
					system.solveDel(null);
				}while(!system.emptyWorkList());

				setDelete(false);
				long delete_time = System.currentTimeMillis() - start_delete;
//				HashSet<IVariable> temp = new HashSet<>();
//				temp.addAll(IPAAbstractFixedPointSolver.changes);
//				system.clearChanges();

				//add
				System.out.println("... Adding SSAInstruction:      "+ inst.toString());
				long start_add = System.currentTimeMillis();
				inst.visit(v);
				do{
					system.solveAdd(null);
					addConstraintsFromNewNodes(null);
				} while (!system.emptyWorkList());

				long add_time = System.currentTimeMillis() - start_add;

				boolean nochange = true;
				Iterator<IVariable> it = IPAAbstractFixedPointSolver.changes.iterator();
				while(it.hasNext()){
					IPAPointsToSetVariable var = (IPAPointsToSetVariable) it.next();
					if(var != null){
						MutableIntSet update = var.getValue();
						PointerKey key = var.getPointerKey();
						MutableIntSet origin = var_pts_map.get(key);
						if(update != null && origin != null){
							if(inst instanceof SSAInvokeInstruction){//new instance created, different id, only test the size of pts
								if(update.size() != origin.size()){
									nochange = false;
									correct = false;
								}
							}else{
								if(!update.sameValue(origin)){
									nochange = false;
									correct = false;
								}
							}
						}else if ((update == null && origin != null)){//(update != null && origin == null)
							nochange = false;
							correct = false;
						}
					}
				}


				if(nochange){
					System.out.println("...... points-to sets are the same before deleting inst and after adding back inst. ");
				}else{
					throw new RuntimeException("****** points-to sets are different before deleting inst and after adding back inst. ");
				}

				if(measurePerformance){
					System.out.println("------> use " + delete_time + "ms to delete the inst, use " + add_time +"ms to add the inst back.");
					total_del = total_del + delete_time;
					total_add = total_add + add_time;
				}

				system.clearChanges();

				System.out.println();

			}catch(Exception e)
			{
				e.printStackTrace();
			}
		}
		return correct;
	}


}
