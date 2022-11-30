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

import org.junit.Assert;

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
import com.ibm.wala.ipa.callgraph.DelegatingContext;
import com.ibm.wala.ipa.callgraph.Entrypoint;
import com.ibm.wala.ipa.callgraph.IAnalysisCacheView;
import com.ibm.wala.ipa.callgraph.impl.AbstractRootMethod;
import com.ibm.wala.ipa.callgraph.propagation.FilteredPointerKey;
import com.ibm.wala.ipa.callgraph.propagation.IPointerOperator;
import com.ibm.wala.ipa.callgraph.propagation.IPointsToSolver;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKeyFactory;
import com.ibm.wala.ipa.callgraph.propagation.PointerAnalysis;
import com.ibm.wala.ipa.callgraph.propagation.PointerKey;
import com.ibm.wala.ipa.callgraph.propagation.PointerKeyFactory;
import com.ibm.wala.ipa.callgraph.propagation.ReceiverInstanceContext;
import com.ibm.wala.ipa.callgraph.propagation.SSAContextInterpreter;
import com.ibm.wala.ipa.callgraph.propagation.ZeroLengthArrayInNode;
import com.ibm.wala.ipa.callgraph.propagation.rta.RTAContextInterpreter;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.ssa.DefUse;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.PhiValue;
import com.ibm.wala.ssa.SSAAbstractInvokeInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.ssa.SymbolTable;
import com.ibm.wala.ssa.Value;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.types.FieldReference;
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
import com.ibm.wala.util.intset.MutableMapping;
//import com.ibm.wala.util.intset.MutableSharedBitVectorIntSet;
import com.ibm.wala.util.warnings.Warning;
import com.ibm.wala.util.warnings.Warnings;

import edu.tamu.wala.increpta.callgraph.impl.IPAExplicitCallGraph;
import edu.tamu.wala.increpta.callgraph.impl.IPAExplicitCallGraph.IPAExplicitNode;
import edu.tamu.wala.increpta.instancekey.EventNormalAllocationInNode;
import edu.tamu.wala.increpta.instancekey.ThreadNormalAllocationInNode;
import edu.tamu.wala.increpta.ipa.callgraph.propagation.IPASSAPropagationCallGraphBuilder.ConstraintVisitor;
import edu.tamu.wala.increpta.ipa.callgraph.propagation.bridge.AbstractIPAPropagationCallGraphBuilder;
import edu.tamu.wala.increpta.operators.IPAAbstractOperator;
import edu.tamu.wala.increpta.operators.IPAAbstractStatement;
import edu.tamu.wala.increpta.operators.IPAAssignOperator;
import edu.tamu.wala.increpta.operators.IPAUnaryOperator;
import edu.tamu.wala.increpta.operators.IPAUnarySideEffect;
import edu.tamu.wala.increpta.operators.IPAUnaryStatement;
import edu.tamu.wala.increpta.operators.OpVisitor;
import edu.tamu.wala.increpta.pointerkey.IPAFilteredPointerKey;
import edu.tamu.wala.increpta.pointerkey.IPAPointerKeyFactory;
import edu.tamu.wala.increpta.util.IPAAbstractFixedPointSolver;
import edu.tamu.wala.increpta.util.JavaUtil;
import edu.tamu.wala.increpta.util.intset.IPAMutableSharedBitVectorIntSet;

public abstract class IPAPropagationCallGraphBuilder extends AbstractIPAPropagationCallGraphBuilder {

	/**
	 * bz: indicate this is incremental if true
	 */
	public void setChange(boolean p){
		super.setChange(p);
		system.setChange(p);
	}
	
	/** bz: flag for deleting constraints
	 * @param delete
	 */
	@Override
	public void setDelete(boolean delete){
		AbstractIPAPropagationCallGraphBuilder.isDelete = delete;
	}
	
	@Override
	public void setFirstDel(boolean is) {
		system.setFirstDel(is);
	}
	
	/**
	 * bz: is using k-cfa (SHARP): ?
	 */
	public void setKCFA(boolean is) {
		system.setKCFA(is);
	}
	
	/**
	 * bz: is using k-obj (SHARP): ?
	 */
	public void setKObj(boolean is) {
		system.setKObj(is);
	}
	
	//////////////////////// original ////////////////////////
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
	 * Set of calls (CallSiteReferences) that are created by entrypoints
	 */
	final protected Set<CallSiteReference> entrypointCallSites = HashSetFactory.make();
	
	public Set<CallSiteReference> getEntryPointCallSites() {
		return entrypointCallSites;
	}

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
	
	private static boolean groupGetPut = false; 

	/**
	 * @param cha governing class hierarchy
	 * @param options governing call graph construction options
	 * @param pointerKeyFactory factory which embodies pointer abstraction policy
	 */
	protected IPAPropagationCallGraphBuilder(IMethod abstractRootMethod, AnalysisOptions options, IAnalysisCacheView cache,
			IPAPointerKeyFactory pointerKeyFactory) {
		super();
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
	
	@Override
	public IAnalysisCacheView getAnalysisCache() {
		return analysisCache;
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
	
	@Override
	public CallGraph makeCallGraph(AnalysisOptions options, IProgressMonitor monitor, int parallelNumThreads, boolean maxparallel) throws IllegalArgumentException,
	CallGraphBuilderCancelException {
		if (options == null) {
			throw new IllegalArgumentException("options is null");
		}
		system = makeSystem(options, parallelNumThreads, maxparallel);
		
		if (DEBUG_GENERAL) {
			System.err.println("Enter makeCallGraph!");
		}

		if (DEBUG_GENERAL) {
			System.err.println("Initialized call graph");
		}

		system.setMinEquationsForTopSort(options.getMinEquationsForTopSort());
		system.setTopologicalGrowthFactor(options.getTopologicalGrowthFactor());
		system.setMaxEvalBetweenTopo(options.getMaxEvalBetweenTopo());

		initialDiscoveredNodes();//discoveredNodes = HashSetFactory.make();
		getDiscoveredNodes().add(callGraph.getFakeRootNode());//discoveredNodes.add(callGraph.getFakeRootNode());

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

		initialDiscoveredNodes();//discoveredNodes = HashSetFactory.make();
		getDiscoveredNodes().add(callGraph.getFakeRootNode());//discoveredNodes.add(callGraph.getFakeRootNode());

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
	
	protected IPAPropagationSystem makeSystem(@SuppressWarnings("unused") AnalysisOptions options, int num, boolean maxparallel) {
		return new IPAPropagationSystem(callGraph, pointerKeyFactory, instanceKeyFactory, num, maxparallel);
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

	
	private static String[] METHODS = {"Ljava/util/concurrent/ForkJoinPool, unlockRunState(II)V", 
			"Ljava/util/zip/ZipFile$ZipFileInputStream, skip(J)J",
			"Ljava/security/cert/CertStore, getInstance(Ljava/lang/String;Ljava/security/cert/CertStoreParameters;)Ljava/security/cert/CertStore;",
			"Lsun/reflect/generics/repository/MethodRepository, <init>(Ljava/lang/String;Lsun/reflect/generics/factory/GenericsFactory;)V",
			"Ljava/lang/Class, copyMethods([Ljava/lang/reflect/Method;)[Ljava/lang/reflect/Method;",
			"Ljava/util/regex/UnicodeProp, forName(Ljava/lang/String;)Ljava/util/regex/UnicodeProp;",
			"Ljava/io/ObjectStreamClass$5, compare(Ljava/io/ObjectStreamClass$MemberSignature;Ljava/io/ObjectStreamClass$MemberSignature;)I",
			"Lsun/security/ssl/HandshakeMessage$CertificateVerify, updateDigest(Ljava/security/MessageDigest;[B[BLjavax/crypto/SecretKey;)V",
			"Ljava/nio/file/spi/FileSystemProvider, <init>()V",
			"Ljava/security/ProtectionDomain$PDCache, get(Ljava/security/ProtectionDomain;)Ljava/security/PermissionCollection;",
			"Lsun/security/krb5/EncryptionKey, parse(Lsun/security/util/DerInputStream;BZ)Lsun/security/krb5/EncryptionKey;",
			"Ljava/text/DecimalFormatSymbols, setInfinity(Ljava/lang/String;)",
			"Ljava/util/Hashtable$Entry, equals(Ljava/lang/Object;)Z",
			"Ljava/util/AbstractCollection, isEmpty()",
			"java/util/stream/StreamOpFlag, fromCharacteristics(Ljava/util/Spliterator;)I",
			"Ljava/text/DateFormatSymbols, getAmPmStrings()[Ljava/lang/String;",
			"Lsun/security/ssl/ServerHandshaker$2, run()Ljava/lang/Object;",
			"Ljava/util/zip/ZipFile$ZipFileInputStream, skip(J)J",
			"Lsun/security/util/BitArray, get(I)Z",
			"Ljava/util/concurrent/ConcurrentHashMap$Traverser, advance()Ljava/util/concurrent/ConcurrentHashMap$Node;",
			"Ljava/lang/Boolean, getBoolean(Ljava/lang/String;)Z",
			"Ljava/text/DecimalFormat, subformat(Ljava/lang/StringBuffer;Ljava/text/Format$FieldDelegate;ZZIIII)Ljava/lang/StringBuffer;",
			"Ljava/util/regex/UnicodeProp, valueOf(Ljava/lang/String;)Ljava/util/regex/UnicodeProp;",
			"Ljava/lang/Boolean, parseBoolean(Ljava/lang/String;)Z)"};
	
	private static String[] CUR_FOCUS = {"Ljava/util/concurrent/ForkJoinPool, <init>",
			"Ljava/util/concurrent/ForkJoinPool, <clinit>"};
	
	private static HashSet<String> DONE = new HashSet<>();
	
	/**
	 * for debug only 
	 * @param n
	 * @return
	 */
	private boolean isSuspicious(CGNode n) {
		for (int i = 0; i < METHODS.length; i++) {
			String method = METHODS[i];
			if(n.toString().contains(method) && !DONE.contains(method)) {
				DONE.add(method);
				print(n);
				return true;
			}
		}
		return false;
	}
	
	private void print(CGNode n) {
		/**
		 * for debug precision
		 */
		System.out.println("===> " + n.getMethod().toString() + "\n  IR: ");
		System.out.println(n.getIR().toString());
		System.out.println("  VALUE: ");
		Value[] values = n.getIR().getSymbolTable().getValues();
		for (int j=0; j<values.length; j++) {
			Value value = values[j];
			if(value != null) {
				if(value instanceof PhiValue)
					System.out.println("v" + j + " : " + ((PhiValue) value).getPhiInstruction().toString());
				else
					System.out.println("v" + j + " : " + value.toString());
			}
		}
		System.out.println();
	}
	
	/**
	 * bz: conditional branch
	 * @param c
	 * @return
	 */
	public PointerKey getPointerKeyAndConstraintForConstantValue(CGNode node, int c) {
		PointerKey key = getPointerKeyForLocal(node, c);
		SymbolTable symbolTable = node.getIR().getSymbolTable();
		if(symbolTable.isConstant(c)) {
			InstanceKey constant = null;
			if(symbolTable.isIntegerConstant(c)) {// boolean will change to integer
				constant = getInstanceKeyForConstant(node, symbolTable.getIntValue(c));
				system.newConstraint(key, constant);
			}else if(symbolTable.isBooleanConstant(c)) {
				constant = getInstanceKeyForConstant(node, symbolTable.getBooleanValue(c));
				system.newConstraint(key, constant);
			}else if(symbolTable.isNullConstant(c)){
				constant = getInstanceKeyForConstant(node, symbolTable.getConstantValue(c));
				system.newConstraint(key, constant);
			}else if(symbolTable.isStringConstant(c)){
				/* TODO: input True as a string: need to handle... 
				 * [ConstantKey:true:<Primordial,Ljava/lang/String>]
				 */
				constant = getInstanceKeyForConstant(node, symbolTable.getStringValue(c));
				system.newConstraint(key, constant);
			}else if(symbolTable.isFloatConstant(c)) {
				constant = getInstanceKeyForConstant(node, symbolTable.getFloatValue(c));
				system.newConstraint(key, constant);
			}else if(symbolTable.isDoubleConstant(c)) {
				constant = getInstanceKeyForConstant(node, symbolTable.getDoubleValue(c));
				system.newConstraint(key, constant);
			}else if(symbolTable.isLongConstant(c)) {
				constant = getInstanceKeyForConstant(node, symbolTable.getLongValue(c));
				system.newConstraint(key, constant);
			}else{
//				System.out.println("NOT handled constant :" + symbolTable.getConstantValue(c));
			}
//			if(constant != null)
//				System.out.println("  => const: " + constant);
		}
		return key;
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
		PointerKey key = pointerKeyFactory.getPointerKeyForArrayContents(I);
		return key;
	}

	

	protected void deAssignInstanceToCatch(PointerKey exceptionVar, Set<IClass> catchClasses, InstanceKey e) {
		if (catches(catchClasses, e.getConcreteType(), cha)) {
			system.delConstraint(exceptionVar, e);
		}
	}

	protected void delAssignmentsForCatchPointerKey(PointerKey exceptionVar, Set<IClass> catchClasses, PointerKey e) {
		if (DEBUG_GENERAL) {
			System.err.println("addAssignmentsForCatch: " + catchClasses);
		}
		// this is tricky ... we want to filter based on a number of classes ... so we can't
		// just used a IPAFilteredPointerKey for the exceptionVar. Instead, we create a new
		// "typed local" for each catch class, and coalesce the results using
		// assignment
		for (IClass c : catchClasses) {
			if (c.getReference().equals(c.getClassLoader().getLanguage().getThrowableType())) {
				system.delConstraint(exceptionVar, assignOperator, e);
			} else {
				IPAFilteredPointerKey typedException = IPATypedPointerKey.make(exceptionVar, c);
				system.delConstraint(typedException, filterOperator, e);
				system.delConstraint(exceptionVar, assignOperator, typedException);
			}
		}
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
				IPAFilteredPointerKey typedException = IPATypedPointerKey.make(exceptionVar, c);
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
	public final static class IPATypedPointerKey implements IPAFilteredPointerKey {

		public int idx = -1;//bz:to save index in the pointerKey map

		@Override
		public void setIdx(int idx) {
			this.idx = idx;
		}

		@Override
		public int getIdx() {
			return idx;
		}
		private final IClass type;

		private final PointerKey base;

		static IPATypedPointerKey make(PointerKey base, IClass type) {
			assert type != null;
			return new IPATypedPointerKey(base, type);
		}

		private IPATypedPointerKey(PointerKey base, IClass type) {
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
			if (obj instanceof IPATypedPointerKey) {
				IPATypedPointerKey other = (IPATypedPointerKey) obj;
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
			if(system.getFirstDel()){
				if(rhs.getValue() == null){
					return NOT_CHANGED;
				}
			}else{
				if(rhs.getChange().size() == 0){
					return NOT_CHANGED;
				}
			}

			boolean changed = false;
			IPAFilteredPointerKey.IPATypeFilter filter = pk.getTypeFilter();
			IPAMutableSharedBitVectorIntSet remaining = null;
			if(system.getFirstDel()){
				remaining = system.computeRemaining(rhs.getValue(), lhs);
			}else{//gonna change to other place
				remaining = system.computeRemaining(rhs.getChange(), lhs);
			}

			//!! lhs can be both a transitive root and assigned by rhs.
			//but we cannot remove value of lhs because of propagation from rhs.
			//the value will lost
			if(!system.getFirstDel() && lhs.getValue() != null){
				if(system.isTransitiveRoot(lhs.getPointerKey())
						&& lhs.getValue().sameValue(remaining))
					return NOT_CHANGED;
			}

			if(!remaining.isEmpty()){
				changed = filter.delFiltered(system, lhs, remaining);
			}
			return changed ? CHANGED : NOT_CHANGED;
		}

		/**
		 * bz: for parallel
		 * @param lhs
		 * @param set
		 * @return
		 */
		public byte evaluateDel(IPAPointsToSetVariable lhs, IPAMutableSharedBitVectorIntSet set) {
			IPAFilteredPointerKey pk = (IPAFilteredPointerKey) lhs.getPointerKey();

			if(set == null || set.size() == 0){
				return NOT_CHANGED;
			}

			boolean changed = false;
			IPAFilteredPointerKey.IPATypeFilter filter = pk.getTypeFilter();
			IPAMutableSharedBitVectorIntSet remaining = system.computeRemaining(set, lhs);
			if(!remaining.isEmpty()){
				changed = filter.delFiltered(system, lhs, remaining);
			}
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

		@Override
		public void translate(OpVisitor visitor, IPAAbstractOperator op, IPAAbstractStatement stmt) {
			if (visitor == null) {
				throw new IllegalArgumentException("visitor is null");
			}
			visitor.translateFilter(this, (IPAUnaryStatement) stmt);
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
	 * bz: used by all other context-insensistive/sensitive algo
	 * @param caller the caller node
	 * @param iKey an abstraction of the receiver of the call (or null if not applicable)
	 * @return the CGNode to which this particular call should dispatch.
	 */
	protected CGNode getTargetForCall(CGNode caller, CallSiteReference site, IClass recv, InstanceKey iKey[]) {
//		if(iKey != null && iKey.length > 0 && site.toString().contains("invokevirtual < Application, Ljava/lang/reflect/Constructor, newInstance([Ljava/lang/Object;)Ljava/lang/Object; >@24")
////				&& iKey[0].toString().contains("ConstantKey:< Application, Lorg/apache/hadoop/hbase/regionserver/HRegionServer, <init>(Lorg/apache/hadoop/conf/Configuration;)V >:")
//				) {
//			System.out.println(iKey[0].toString());
//		}
//		
//		if(iKey != null && iKey.length > 0 && site.toString().contains("invokevirtual < Application, Ljava/lang/Class, getConstructor([Ljava/lang/Class;)Ljava/lang/reflect/Constructor; >@11") ) {
//			System.out.println();
//		}
		
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
//			CGNode node = getCallGraph().findOrCreateNode(targetMethod, targetContext);
//			
//			if(node.toString().contains("Ljava/lang/reflect/Constructor, newInstance([Ljava/lang/Object;)Ljava/lang/Object; > Context: CallStringContextPair: [ org.apache.hadoop.hbase.regionserver.HRegionServer.constructRegionServer(Ljava/lang/Class;Lorg/apache/hadoop/conf/Configuration;)Lorg/apache/hadoop/hbase/regionserver/HRegionServer;@24 ]:DelegatingContext [A=CallStringContext: [ org.apache.hadoop.hbase.regionserver.HRegionServer.constructRegionServer(Ljava/lang/Class;Lorg/apache/hadoop/conf/Configuration;)Lorg/apache/hadoop/hbase/regionserver/HRegionServer;@24 ], B=Everywhere]")
//					|| node.toString().contains("Ljava/lang/reflect/Constructor, newInstance([Ljava/lang/Object;)Ljava/lang/Object; > Context: DelegatingContext [A=DelegatingContext [A=ReceiverInstanceContext<[ConstantKey:< Application, Lorg/apache/hadoop/hbase/regionserver/HRegionServer, <init>(Lorg/apache/hadoop/conf/Configuration;)V >:<Primordial,Ljava/lang/reflect/Constructor>]>, B=CallStringContext: [ org.apache.hadoop.hbase.regionserver.HRegionServer.constructRegionServer(Ljava/lang/Class;Lorg/apache/hadoop/conf/Configuration;)Lorg/apache/hadoop/hbase/regionserver/HRegionServer;@24 ]], B=Everywhere]"))
//				System.out.println();
//			
//			return node;
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

	public InstanceKeyFactory getInstanceKeyFactory() {
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
	 * bz: get over of instanceKeyFactory
	 * no smush for these thread instances.
	 */
	public InstanceKey getInstanceKeyForThreadAllocation(CGNode node, NewSiteReference allocation) {
		IClass type = options.getClassTargetSelector().getAllocatedTarget(node, allocation);
	    if (type == null) {
	      return null;
	    }
		return new ThreadNormalAllocationInNode(node, allocation, type);
	}
	
	public InstanceKey getInstanceKeyForEventAllocation(CGNode node, NewSiteReference allocation) {
		IClass type = options.getClassTargetSelector().getAllocatedTarget(node, allocation);
	    if (type == null) {
	      return null;
	    }
		return new EventNormalAllocationInNode(node, allocation, type);
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

	public <T> InstanceKey getInstanceKeyForConstant(CGNode node, T S) {//bz
		TypeReference type = node.getMethod().getDeclaringClass().getClassLoader().getLanguage().getConstantTypeForConstantValue(S);
		return instanceKeyFactory.getInstanceKeyForConstant(type, S);
	}

	public InstanceKey getInstanceKeyForMetadataObject(Object obj, TypeReference objType) {
		return instanceKeyFactory.getInstanceKeyForMetadataObject(obj, objType);
	}

	/**
	 * Binary op: <dummy>:= ArrayLoad( &lt;arrayref>) Side effect: Creates new equations.
	 */
	public final class ArrayLoadOperator extends IPAUnarySideEffect implements IPointerOperator {
		protected MutableIntSet priorInstances = rememberGetPutHistory ? IntSetUtil.make() : null;

		@Override
		public String toString() {
			return "ArrayLoad";
		}

		public ArrayLoadOperator(IPAPointsToSetVariable def) {
			super(def);
		}

		@Override
		public byte evaluate(IPAPointsToSetVariable rhs) {
			if (DEBUG_ARRAY_LOAD) {
				IPAPointsToSetVariable def = getFixedSet();
				String S = "EVAL ArrayLoad " + rhs.getPointerKey() + " " + def.getPointerKey();
				System.err.println(S);
				System.err.println("EVAL ArrayLoad " + def + " " + rhs);
//				if (priorInstances != null) {
//					System.err.println("prior instances: " + priorInstances + " " + priorInstances.getClass());
//				}
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
					sideEffect.b |= system.newFieldRead(dVal, assignOperator, p, rhs);
				}
			};
			
			HashSet<PointerKey> rhss = new HashSet<>();
			IntSetAction action_group = new IntSetAction() {
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
					rhss.add(p);
				}
			};
			
			if(groupGetPut) {
				if (priorInstances != null) {
					rhs.getValue().foreachExcluding(priorInstances, action_group);
					priorInstances.addAll(rhs.getValue());
				} else {
					rhs.getValue().foreach(action_group);
				}
				return system.newFieldReadGroup(dVal, assignOperator, rhss)? SIDE_EFFECT_MASK : NOT_CHANGED;
			}else {
				if (priorInstances != null) {
					rhs.getValue().foreachExcluding(priorInstances, action);
					priorInstances.addAll(rhs.getValue());
				} else {
					rhs.getValue().foreach(action);
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

			if (DEBUG_ARRAY_LOAD) {
				IPAPointsToSetVariable def = getFixedSet();
				String S = "DEL EVAL ArrayLoad " + rhs.getPointerKey() + " " + def.getPointerKey();
				System.err.println(S);
				System.err.println("DEl EVAL ArrayLoad " + def + " " + rhs);
				if (priorInstances != null) {
					System.err.println("prior instances: " + priorInstances + " " + priorInstances.getClass());
				}
			}

			if(system.getFirstDel()){
				if (rhs.size() == 0) {
					return NOT_CHANGED;
				}
			}else{
				if (rhs.getChange().size() == 0) {
					return NOT_CHANGED;
				}
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
			priorInstances.foreach(action);
			if(rhss.size() != 0)
				sideEffect_del.b |= system.delConstraintHasMultiR(def, assignOperator, rhss, delset, rhs);
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
		
		@Override
		public void translate(OpVisitor visitor, IPAAbstractOperator op,IPAAbstractStatement stmt) {
			if (visitor == null) {
				throw new IllegalArgumentException("visitor is null");
			}
			visitor.translateArrayLoad(this, (IPAUnaryStatement) stmt);
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
					sideEffect |= system.newFieldWrite(p, assignOperator, pVal, rhs);
				} else {
					sideEffect |= system.newFieldWrite(p, filterOperator, pVal, rhs);
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

			if(system.getFirstDel()){
				if (rhs.size() == 0) {
					return NOT_CHANGED;
				}
			}else{
				if (rhs.getChange().size() == 0) {
					return NOT_CHANGED;
				}
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
					sideEffect_del |= system.delFieldWrite(p, assignOperator, pVal, rhs);
				} else {
					sideEffect_del |= system.delFieldWrite(p, filterOperator, pVal, rhs);
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
		
		@Override
		public void translate(OpVisitor visitor, IPAAbstractOperator op, IPAAbstractStatement stmt) {
			if (visitor == null) {
				throw new IllegalArgumentException("visitor is null");
			}
			visitor.translateArrayStore(this, (IPAUnaryStatement) stmt);
		}
	}

	/**
	 * Binary op: <dummy>:= GetField( <ref>) Side effect: Creates new equations.
	 */
	public class GetFieldOperator extends IPAUnarySideEffect implements IPointerOperator {
		private final IField field;

		protected MutableIntSet priorInstances = rememberGetPutHistory ? IntSetUtil.make() : null;

		public GetFieldOperator(IField field, IPAPointsToSetVariable def) {
			super(def);
			this.field = field;
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
							sideEffect.b |= system.newFieldRead(dVal, assignOperator, p, rhs);
						}
					}
				}
			};

			final MutableIntSet targets = IntSetUtil.getDefaultIntSetFactory().make();
			final ArrayList<IPAPointsToSetVariable> rhss_parallel = new ArrayList<>();
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
							rhss_parallel.add(ptv);
						}
					}
				}
			};
			
			final HashSet<PointerKey> rhss = new HashSet<>();
			IntSetAction action_group = new IntSetAction() {
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
							rhss.add(p);
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
						sideEffect.b |= system.addConstraintHasMultiR_Parallel(def, assignOperator, rhss_parallel, targets, rhs);
				}
			}else{
				if(groupGetPut) {
					if (priorInstances != null) {
						value.foreachExcluding(priorInstances, action_group);
						priorInstances.addAll(value);
					} else {
						value.foreach(action_group);
					}
					return system.newFieldReadGroup(dVal, assignOperator, rhss)? SIDE_EFFECT_MASK : NOT_CHANGED;
				}else {
					if (priorInstances != null) {
						value.foreachExcluding(priorInstances, action);
						priorInstances.addAll(value);
					} else {
						value.foreach(action);
					}
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
			if(system.getFirstDel()){
				if (ref.size() == 0) {
					return NOT_CHANGED;
				}
			}else{
				if (ref.getChange().size() == 0 ) {
					return NOT_CHANGED;
				}
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
			}else{
				rhs.getChange().foreach(action);
			}
			priorInstances.foreach(action);

			if(rhss.size() != 0)
				sideEffect_del.b |= system.delConstraintHasMultiR(def, assignOperator, rhss, delset, rhs);
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

		public IField getField() {
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
		
		@Override
		public void translate(OpVisitor visitor, IPAAbstractOperator op, IPAAbstractStatement stmt) {
			if (visitor == null) {
				throw new IllegalArgumentException("visitor is null");
			}
			visitor.translateGetField(this, (IPAUnaryStatement) stmt);
		}
	}
	
	private static final FieldReference THREAD_TARGET = FieldReference.findOrCreate(ClassLoaderReference.Primordial, "Ljava/lang/Thread", "target", "Ljava/lang/Runnable"); 

	/**
	 * Operator that represents a putfield
	 */
	public class PutFieldOperator extends IPAUnarySideEffect implements IPointerOperator {
		private final IField field;

		protected MutableIntSet priorInstances = rememberGetPutHistory ? IntSetUtil.make() : null;

		@Override
		public String toString() {
			return "PutField" + getField();
		}

		public PutFieldOperator(IField field, IPAPointsToSetVariable val) {
			super(val);
			this.field = field;
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
							sideEffect.b |= system.newFieldWrite(p, assign, pVal, rhs);
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
							lhss.add(pptv);
						}
					}
				}
			};
			
			final HashSet<PointerKey> lhss_group = new HashSet<>();
			IntSetAction action_group = new IntSetAction() {
				@Override
				public void act(int i) {
					InstanceKey I = system.getInstanceKey(i);
					if (!representsNullType(I)) {
						PointerKey p = getPointerKeyForInstanceField(I, getField());
						if(p != null){
							lhss_group.add(p);
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
					system.addConstraintHasMultiL_Parallel(lhss, assignOperator, val, targets, rhs);
				}
			}else{
				if(groupGetPut) {
					if (priorInstances != null) {
						value.foreachExcluding(priorInstances, action);
						priorInstances.addAll(value);
					} else {
						value.foreach(action);
					}
					return system.newFieldWriteGroup(lhss_group, assignOperator, pVal)? SIDE_EFFECT_MASK : NOT_CHANGED;
				}else {
					if (priorInstances != null) {
						value.foreachExcluding(priorInstances, action);
						priorInstances.addAll(value);
					} else {
						value.foreach(action);
					}
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

			if(system.getFirstDel()){
				if (rhs.size() == 0) {
					return NOT_CHANGED;
				}
			}else{
				if (rhs.getChange().size() == 0) {
					return NOT_CHANGED;
				}
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
							sideEffect_del.b |= system.delFieldWrite(p, assign, pVal, rhs);//bz: optimize
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
			if(value.size() < 10){
				value.foreach(action);
				//always do this for all instances
				priorInstances.foreach(action);
			}else{
				value.foreach(action2);
				MutableIntSet targets = IntSetUtil.getDefaultIntSetFactory().make();
				if(val.getValue() != null){
					targets.addAll(val.getValue());
				}
				//always do this for all instances
				priorInstances.foreach(action2);
				system.delConstraintHasMultiL(lhss, assignOperator, val, targets, rhs);
			}
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
		public IField getField() {
			return field;
		}

		@Override
		protected boolean isLoadOperator() {
			return false;
		}
		
		@Override
		public void translate(OpVisitor visitor, IPAAbstractOperator op, IPAAbstractStatement stmt) {
			if (visitor == null) {
				throw new IllegalArgumentException("visitor is null");
			}
			visitor.translatePutField(this, (IPAUnaryStatement) stmt);
		}
	}

	/**
	 * Update the points-to-set for a field to include a particular instance key.
	 */
	public final class InstancePutFieldOperator extends IPAUnaryOperator<IPAPointsToSetVariable> implements IPointerOperator {
		final private IField field;

		final private InstanceKey instance;

		protected MutableIntSet priorInstances = rememberGetPutHistory ? IntSetUtil.make() : null;

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

			if(system.getFirstDel()){
				if (ref.size() == 0) {
					return NOT_CHANGED;
				}
			}else{
				if (ref.getChange().size() == 0) {
					return NOT_CHANGED;
				}
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
			priorInstances.foreach(action);

			MutableIntSet delset = IntSetUtil.make();
			delset.add(system.findOrCreateIndexForInstanceKey(instance));
			system.delConstraintHasMultiInstanceL(lhss, delset, ref);
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
		
		//bz: db
		public IField getField() {
			return field;
		}
		
		//bz: db
		public InstanceKey getInstance() {
			return instance;
		}

		@Override
		public void translate(OpVisitor visitor, IPAAbstractOperator op, IPAAbstractStatement stmt) {
			if (visitor == null) {
				throw new IllegalArgumentException("visitor is null");
			}
			visitor.translateInstancePutField(this, (IPAUnaryStatement) stmt);
		}
	}

	/**
	 * Update the points-to-set for an array contents to include a particular instance key.
	 */
	public final class InstanceArrayStoreOperator extends IPAUnaryOperator<IPAPointsToSetVariable> implements IPointerOperator {
		final private InstanceKey instance;

		protected MutableIntSet priorInstances = rememberGetPutHistory ? IntSetUtil.make() : null;

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

			if(system.getFirstDel()){
				if (arrayref.size() == 0) {
					return NOT_CHANGED;
				}
			}else{
				if (arrayref.getChange().size() == 0) {
					return NOT_CHANGED;
				}
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

		//bz: db
		public InstanceKey getInstance() {
			return instance;
		}
		
		@Override
		public void translate(OpVisitor visitor, IPAAbstractOperator op, IPAAbstractStatement stmt) {
			if (visitor == null) {
				throw new IllegalArgumentException("visitor is null");
			}
			visitor.translateInstanceArrayStore(this, (IPAUnaryStatement) stmt);
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
	 * Add constraints when the interpretation of a node changes (i.e. from reflection)
	 * @param monitor
	 * @throws CancelException
	 */
	public void addConstraintsFromChangedNode(CGNode node, IProgressMonitor monitor) throws CancelException {
		unconditionallyAddConstraintsFromNode(node, monitor);
	}
	
	protected static class MutableBoolean {
		// a horrendous hack since we don't have closures
		boolean b = false;
	}

}
