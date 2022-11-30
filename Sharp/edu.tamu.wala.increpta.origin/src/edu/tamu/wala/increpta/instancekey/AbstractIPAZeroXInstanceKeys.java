package edu.tamu.wala.increpta.instancekey;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IField;
import com.ibm.wala.classLoader.NewSiteReference;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKeyFactory;
import com.ibm.wala.ipa.callgraph.propagation.rta.RTAContextInterpreter;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.types.TypeName;
import com.ibm.wala.types.TypeReference;
import com.ibm.wala.util.collections.HashMapFactory;
import com.ibm.wala.util.collections.HashSetFactory;
import com.ibm.wala.util.collections.Iterator2Iterable;

public abstract class AbstractIPAZeroXInstanceKeys implements InstanceKeyFactory {

	
	private final static TypeName JavaLangStringBufferName = TypeName.string2TypeName("Ljava/lang/StringBuffer");

	public final static TypeReference JavaLangStringBuffer = TypeReference.findOrCreate(ClassLoaderReference.Primordial,
			JavaLangStringBufferName);

	private final static TypeName JavaLangStringBuilderName = TypeName.string2TypeName("Ljava/lang/StringBuilder");

	public final static TypeReference JavaLangStringBuilder = TypeReference.findOrCreate(ClassLoaderReference.Primordial,
			JavaLangStringBuilderName);

	private final static TypeName JavaLangAbstractStringBuilderName = TypeName.string2TypeName("Ljava/lang/AbstractStringBuilder");

	public final static TypeReference JavaLangAbstractStringBuilder = TypeReference.findOrCreate(ClassLoaderReference.Primordial,
			JavaLangAbstractStringBuilderName);

	/**
	 * The NONE policy is not allocation-site based
	 */
	public static final int NONE = 0;

	/**
	 * An ALLOCATIONS - based policy distinguishes instances by allocation site. Otherwise, the policy distinguishes instances by
	 * type.
	 */
	public static final int ALLOCATIONS = 1;

	/**
	 * A policy variant where String and StringBuffers are NOT disambiguated according to allocation site.
	 */
	public static final int SMUSH_STRINGS = 2;

	/**
	 * A policy variant where {@link Throwable} instances are NOT disambiguated according to allocation site.
	 * 
	 */
	public static final int SMUSH_THROWABLES = 4;

	/**
	 * A policy variant where if a type T has only primitive instance fields, then instances of type T are NOT disambiguated by
	 * allocation site.
	 */
	public static final int SMUSH_PRIMITIVE_HOLDERS = 8;

	/**
	 * This variant counts the N, number of allocation sites of a particular type T in each method. If N &gt; SMUSH_LIMIT, then these N
	 * allocation sites are NOT distinguished ... instead there is a single abstract allocation site for &lt;N,T&gt;
	 * 
	 * Probably the best choice in many cases.
	 */
	public static final int SMUSH_MANY = 16;

	/**
	 * Should we use constant-specific keys?
	 */
	public static final int CONSTANT_SPECIFIC = 32;

	/**
	 * When using smushing, how many sites in a node will be kept distinct before smushing?
	 */
	private final int SMUSH_LIMIT = 25;

	/**
	 * The policy choice for instance disambiguation
	 */
	private final int policy;

	/**
	 * The governing class hierarchy
	 */
	private final IClassHierarchy cha;

	/**
	 * An object which interprets nodes in context.
	 */
	final private RTAContextInterpreter contextInterpreter;

	/**
	 * a Map from CGNode-&gt;Set&lt;IClass&gt; that should be smushed.
	 */
	protected final Map<CGNode, Set<IClass>> smushMap = HashMapFactory.make();
	
	
	/**
	 * Diff: handle class-based instance with origin/thread
	 * @param options
	 * @param cha
	 * @param contextInterpreter
	 * @param policy
	 */
	public AbstractIPAZeroXInstanceKeys(AnalysisOptions options, IClassHierarchy cha, RTAContextInterpreter contextInterpreter, int policy) {
		if (options == null) {
			throw new IllegalArgumentException("null options");
		}
		this.policy = policy;
		if (disambiguateConstants()) {
			// this is an ugly hack. TODO: clean it all up.
			options.setUseConstantSpecificKeys(true);
		}
		this.cha = cha;
		this.contextInterpreter = contextInterpreter;
	}
	

	public IClassHierarchy getClassHierarchy() {
		return cha;
	}
	
	public int getSMUSHLIMIT() {
		return SMUSH_LIMIT;
	}
	
	public RTAContextInterpreter getContextInterpreter() {
		return contextInterpreter;
	}
	
	/**
	 * @return true iff the policy smushes some allocation sites
	 */
	public boolean smushMany() {
		return (policy & SMUSH_MANY) > 0;
	}

	public boolean allocationPolicy() {
		return (policy & ALLOCATIONS) > 0;
	}

	private boolean smushStrings() {
		return (policy & SMUSH_STRINGS) > 0;
	}

	public boolean smushThrowables() {
		return (policy & SMUSH_THROWABLES) > 0;
	}

	private boolean smushPrimHolders() {
		return (policy & SMUSH_PRIMITIVE_HOLDERS) > 0;
	}

	public boolean disambiguateConstants() {
		return (policy & CONSTANT_SPECIFIC) > 0;
	}

	

	public static boolean isReflectiveType(TypeReference type) {
		return type.equals(TypeReference.JavaLangReflectConstructor) || type.equals(TypeReference.JavaLangReflectMethod);
	}
	
	/**
	 * A class is "interesting" iff we distinguish instances of the class
	 */
	public boolean isInteresting(IClass C) {
		if (!allocationPolicy()) {
			return false;
		}
		if (smushStrings() && isStringish(C)) {
			return false;
		} else if (smushThrowables() && (isThrowable(C) || isStackTraceElement(C))) {
			return false;
		} else if (smushPrimHolders() && allFieldsArePrimitive(C)) {
			return false;
		}
		return true;
	}

	public static boolean isStringish(IClass C) {
		if (C == null) {
			throw new IllegalArgumentException("C is null");
		}
		return C.getReference().equals(TypeReference.JavaLangString) || C.getReference().equals(JavaLangStringBuffer)
				|| C.getReference().equals(JavaLangStringBuilder) || C.getReference().equals(JavaLangAbstractStringBuilder);
	}

	public static boolean isThrowable(IClass c) {
		if (c == null) {
			throw new IllegalArgumentException("null c");
		}
		return c.getClassHierarchy().isSubclassOf(c, c.getClassHierarchy().lookupClass(TypeReference.JavaLangThrowable));
	}


	public static boolean isStackTraceElement(IClass c) {
		if (c == null) {
			throw new IllegalArgumentException("C is null");
		}
		return c.getReference().equals(TypeReference.JavaLangStackTraceElement);
	}


	private boolean allFieldsArePrimitive(IClass c) {
		if (c.isArrayClass()) {
			TypeReference t = c.getReference().getArrayElementType();
			return t.isPrimitiveType();
		}
		if (c.getReference().equals(TypeReference.JavaLangObject)) {
			return true;//bz: almost all the super types are java.lang.object.... ???
		}
		for (IField f : c.getDeclaredInstanceFields()) {
			if (f.getReference().getFieldType().isReferenceType()) {
				return false;
			}
		}
		return allFieldsArePrimitive(c.getSuperclass());
	}


	/**
	 * side effect: populates the smush map.
	 * 
	 * @return true iff the node contains too many allocation sites of type c
	 */
	public boolean exceedsSmushLimit(IClass c, CGNode node) {
		Set<IClass> s = smushMap.get(node);
		if (s == null) {
			Map<IClass, Integer> count = countAllocsByType(node);
			HashSet<IClass> smushees = HashSetFactory.make(5);
			for (Map.Entry<IClass, Integer> e : count.entrySet()) {
				Integer i = e.getValue();
				if (i.intValue() > getSMUSHLIMIT()) {
					smushees.add(e.getKey());
				}
			}
			s = smushees.isEmpty() ? Collections.<IClass> emptySet() : smushees;
			smushMap.put(node, s);
		}
		return s.contains(c);
	}
	


	/**
	 * @return Map: IClass -&gt; Integer, the number of allocation sites for each type.
	 */
	private Map<IClass, Integer> countAllocsByType(CGNode node) {
		Map<IClass, Integer> count = HashMapFactory.make();
		for (NewSiteReference n : Iterator2Iterable.make(getContextInterpreter().iterateNewSites(node))) {
			IClass alloc = getClassHierarchy().lookupClass(n.getDeclaredType());
			if (alloc != null) {
				Integer old = count.get(alloc);
				if (old == null) {
					count.put(alloc, Integer.valueOf(1));
				} else {
					count.put(alloc, Integer.valueOf(old.intValue() + 1));
				}
			}
		}
		return count;
	}
}
