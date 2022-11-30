package edu.tamu.wala.increpta.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Stack;

import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.classLoader.NewSiteReference;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.cha.ClassHierarchy.Node;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSAAbstractInvokeInstruction;
import com.ibm.wala.ssa.SSACFG;
import com.ibm.wala.ssa.SSACFG.BasicBlock;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.types.TypeName;
import com.ibm.wala.types.TypeReference;
import com.ibm.wala.util.graph.dominators.NumberedDominators;
import com.ibm.wala.util.strings.Atom;

import edu.tamu.wala.increpta.callgraph.impl.IPAExplicitCallGraph;
import edu.tamu.wala.increpta.ipa.callgraph.propagation.IPAPropagationCallGraphBuilder;

public class JavaUtil {

	private static boolean DEBUG = false;
	
	public static boolean FURTHER_LEVEL_OF_LOOP = false;

	private static final String OBJECT_GETCLASS_SIGNATURE = "java.lang.Object.getClass()Ljava/lang/Class;"; //$NON-NLS-1$
	public static final String EXTENSION_CLASSLOADER_NAME = "Extension"; //$NON-NLS-1$
	public static final String PRIMORDIAL_CLASSLOADER_NAME = "Primordial"; //$NON-NLS-1$
	public static final String APPLICATION_CLASSLOADER_NAME = "Application"; //$NON-NLS-1$


	private static IPAPropagationCallGraphBuilder builder;
	private static IClassHierarchy classHierarchy;
	public static void setBuilder(IPAPropagationCallGraphBuilder builder) {
		JavaUtil.builder = builder;
		JavaUtil.classHierarchy = builder.getClassHierarchy();
	}
	

	private static ArrayList<BasicBlock> union(ArrayList<BasicBlock> l1, ArrayList<BasicBlock> l2){
		Iterator<BasicBlock> it = l2.iterator();
		while (it.hasNext()){
			BasicBlock next = it.next();
			if (!l1.contains(next)){
				l1.add(next);
			}
		}
		return l1;
	}

	private static ArrayList<BasicBlock> getLoopBodyFor(SSACFG cfg, BasicBlock header, ISSABasicBlock node){
		ArrayList<BasicBlock> loopBody = new ArrayList<BasicBlock>();
		Stack<ISSABasicBlock> stack = new Stack<ISSABasicBlock>();

		loopBody.add(header);
		stack.push(node);

		while (!stack.isEmpty()){
			BasicBlock next = (BasicBlock)stack.pop();
			if (!loopBody.contains(next)){
				// add next to loop body
				loopBody.add(0, next);
				// put all preds of next on stack
				Iterator<ISSABasicBlock> it = cfg.getPredNodes(next);
				while (it.hasNext()){
					stack.push(it.next());
				}
			}
		}

		assert (node==header && loopBody.size()==1) || loopBody.get(loopBody.size()-2)==node;
		assert loopBody.get(loopBody.size()-1)==header;

		return loopBody;
	}




	/**
	 * is or extend
	 * @param reference
	 * @return
	 */
	public static boolean doExtendThreadRelatedClass(TypeReference reference) {
		IClass klass = findIClassFromType(reference);
		return klass == null ? 
				false : (doExtendThreadRelatedClass(klass)); 
	}
	
	/**
	 * is or extend Runnable or Callable
	 * @param klass
	 * @return
	 */
	public static boolean doExtendThreadRelatedClass(IClass klass) {
		return implementsCallableInterface(klass) 
				|| implementsRunnableInterface(klass); 
	}

	public static boolean implementsRunnableInterface(IClass klass) {
		if(klass == null)
			return false;
		if(isRunnableInterface(klass))
			return true;
		for (IClass implementedInterface : klass.getAllImplementedInterfaces()) {
			if (isRunnableInterface(implementedInterface)) {
				return true;
			}
		}
		return false;
	}

	private static boolean isRunnableInterface(IClass interfaceClass) {
		return getEnclosingNonanonymousClassName(interfaceClass.getName()).equals("java.lang.Runnable");
	}

	public static boolean implementsCallableInterface(IClass klass) {
		if(klass == null)
			return false;
		if(isCallableInterface(klass))
			return true;
		for (IClass implementedInterface : klass.getAllImplementedInterfaces()) {
			if (isCallableInterface(implementedInterface)) {
				return true;
			}
		}
		return false;
	}

	private static boolean isCallableInterface(IClass interfaceClass) {
		return getEnclosingNonanonymousClassName(interfaceClass.getName()).equals("java.util.concurrent.Callable");
	}
	
	/**
	 * bz: all the implemented interfaces for jdk thread pools and above join methods
	 * @param klass
	 * @return
	 */
	public static boolean implementsExecutorServiceInterface(IClass klass) {
		if(klass == null)
			return false;
		if(isExecutorServiceInterface(klass))
			return true;
		for (IClass implementedInterface : klass.getAllImplementedInterfaces()) {
			if (isExecutorServiceInterface(implementedInterface)) {
				return true;
			}
		}
		return false;
	}

	private static boolean isExecutorServiceInterface(IClass interfaceClass) {
		return getEnclosingNonanonymousClassName(interfaceClass.getName()).equals("java.util.concurrent.ExecutorService");
	}
	

	private static String getEnclosingNonanonymousClassName(TypeName typeName) {
		String packageName = typeName.getPackage().toString().replaceAll("/", ".");
		int indexOfOpenParen = packageName.indexOf('(');
		if (indexOfOpenParen != -1) {
			int indexOfLastPackageSeparator = packageName.lastIndexOf('.', indexOfOpenParen);
			return packageName.substring(0, indexOfLastPackageSeparator);
		}
		String className = typeName.getClassName().toString();
		int indexOfDollarSign = className.indexOf('$');
		if (indexOfDollarSign != -1) {
			className = className.substring(0, indexOfDollarSign);
		}
		return packageName + "." + className;
	}
	/**
	 * bz: klass is a thread creation from an app executor that extends or implements classes from java.util.concurrent.* (all kinds of executors)
	 * @param klass
	 * @return
	 */
	public static boolean fromJDKConcurrentExecutors(IClass klass) {
		return implementsJDKConcurrentExecutors(klass) || doExtendsJDKConcurrentExecutors(klass);
	}
	
	private static boolean doExtendsJDKConcurrentExecutors(IClass klass) {
		if(extendsJDKConcurrentExecutors(klass))
			return true;
		IClass superclass = klass.getSuperclass();
		if (superclass == null) {
			return false;
		}
		if (extendsJDKConcurrentExecutors(superclass)) {
			return true;
		}
		return doExtendsJDKConcurrentExecutors(superclass);
	}
	
	private static boolean extendsJDKConcurrentExecutors(IClass klass) {
		return getEnclosingNonanonymousClassName(klass.getName()).contains("java.util.concurrent");
	}
	

	private static boolean implementsJDKConcurrentExecutors(IClass klass) {
		if(isJDKConcurrentExecutors(klass))
			return true;
		for (IClass implementedInterface : klass.getAllImplementedInterfaces()) {
			if (isJDKConcurrentExecutors(implementedInterface)) {
				return true;
			}
		}
		return false;
	}

	private static boolean isJDKConcurrentExecutors(IClass interfaceClass) {
		return getEnclosingNonanonymousClassName(interfaceClass.getName()).contains("java.util.concurrent");
	}
	
	/**
	 * is or extend Thread
	 * @param klass
	 * @return
	 */
	public static boolean doExtendThreadClass(TypeReference type) {
		IClass klass = findIClassFromType(type);
		return klass == null ? 
				false : (doExtendThreadClass(klass)); 
	}
	
	/**
	 * is or extend Thread
	 * @param klass
	 * @return
	 */
	public static boolean doExtendThreadClass(IClass klass) {
		if(isThreadClass(klass))
			return true;
		IClass superclass = klass.getSuperclass();
		if (superclass == null) {
			return false;
		}
		if (isThreadClass(superclass)) {
			return true;
		}
		return doExtendThreadClass(superclass);
	}

	private static boolean isThreadClass(IClass klass) {
		return getEnclosingNonanonymousClassName(klass.getName()).equals("java.lang.Thread");
	}

    public static IClass findIClassFromString(String sClass) {
    	TypeReference klass = TypeReference.find(sClass);
		return klass == null ? null : findIClassFromType(klass);
    }
	
	public static IClass findIClassFromNewSite(NewSiteReference newSite) {
		TypeReference klass = newSite.getDeclaredType();
		return klass == null ? null : findIClassFromType(klass);
	}
	
	public static IClass findIClassFromType(TypeReference klass) {
		Node node = classHierarchy.getMap().get(klass);//classHierarchy.getMap() is Map<TypeReference, Node>
		if(node == null && klass.getClassLoader().equals(ClassLoaderReference.Application)) { //&& isJDKType(klass)
			//bz: this is highly possible that:
			// - invokespecial < Application, Ljava/lang/StringBuilder, <init>()V >@7    ==> here, class loader is not matched 
		    //    -> Node: < Primordial, Ljava/lang/StringBuilder, <init>()V > 
			klass = TypeReference.findOrCreate(ClassLoaderReference.Primordial, klass.getName());
			node = classHierarchy.getMap().get(klass);
		}
		return node == null ? null : node.getJavaClass();
	}

	public static boolean isObjectGetClass(IMethod method) {
		return isObjectGetClass(method.getSignature());
	}

	private static boolean isObjectGetClass(String methodSignature) {
		return methodSignature.equals(OBJECT_GETCLASS_SIGNATURE);
	}


	public static boolean isLibraryClass(IClass klass) {
		return isExtension(klass.getClassLoader().getName());
	}

	private static boolean isExtension(Atom classLoaderName) {
		return classLoaderName.toString().equals(EXTENSION_CLASSLOADER_NAME);
	}
	
	/**
	 * bz: 
	 * java.util.Map.Entry<Object, Object> -> getKey(), getValue()
	 * @param klass
	 * @return this is a class of jdk collection. For classes in java.util.concurrent.*, assume they are safe ?? not now
	 */
	public static boolean isJDKUtilCollectionClass(TypeReference type) {
//		String c_name = klass.toString();
//		return c_name.contains("Ljava/util") 
//				&& !c_name.contains("concurrent")
//				&& (c_name.contains("List") || c_name.contains("Map") || c_name.contains("Set"));
		IClass klass = findIClassFromType(type); 
		return klass == null ? false : (implementsCollectionInterface(klass) || implementsMapInterface(klass));
	}
	
	/**
	 * e.g., Set, List, SortedSet, HashSet, TreeSet, ArrayList, LinkedList, Vector, Collections, Arrays, AbstractCollection
	 * @param klass
	 * @return
	 */
	public static boolean implementsCollectionInterface(IClass klass) {
		for (IClass implementedInterface : klass.getAllImplementedInterfaces()) {
			if (isCollectionInterface(implementedInterface)) {
				return true;
			}
		}
		return false;
	}

	private static boolean isCollectionInterface(IClass interfaceClass) {
		return getEnclosingNonanonymousClassName(interfaceClass.getName()).equals("java.util.Collection");
	}
	
	
	/**
	 * e.g., Map, SortedMap,  
	 * @param klass
	 * @return
	 */
	public static boolean implementsMapInterface(IClass klass) {
		for (IClass implementedInterface : klass.getAllImplementedInterfaces()) {
			if (isMapInterface(implementedInterface)) {
				return true;
			}
		}
		return false;
	}

	private static boolean isMapInterface(IClass interfaceClass) {
		return getEnclosingNonanonymousClassName(interfaceClass.getName()).equals("java.util.Map");
	}
	
	
	/**
	 * bz: include java.util.concurrent.*
	 * @param klass
	 * @return
	 */
	public static boolean isAnyJDKUtilCollectionClass(TypeReference klass) {
		String c_name = klass.toString();
		return c_name.contains("Ljava/util") 
				&& (c_name.contains("List") || c_name.contains("Map") || c_name.contains("Set"));
	}
	
	/**
	 * string matching with "Ljava"
	 * @param typ
	 * @return
	 */
	public static boolean isJDKType(TypeReference typ) {
		return typ.getName().getPackage().toString().startsWith("java/");
	}
	
	/**
	 * check class loader 
	 */
	public static boolean isJDKClass(IClass klass) {
		return isPrimordial(klass.getClassLoader().getName());
	}

	private static boolean isPrimordial(Atom classLoaderName) {
		return classLoaderName.toString().equals(PRIMORDIAL_CLASSLOADER_NAME);
	}
	
	public static boolean isApplicationClass(IClass klass) {
		return isApplication(klass.getClassLoader().getName());
	}

	private static boolean isApplication(Atom classLoaderName) {
		return classLoaderName.toString().equals(APPLICATION_CLASSLOADER_NAME);
	}

	public static boolean considerJDKThreadRelatedClass(IClass klass) {
		String c_name = klass.toString(); 
		if(isApplicationClass(klass))
			return true;
		else if(c_name.contains("Ljava/util/concurrent") || c_name.contains("Ljava/lang/invoke/LambdaMetafactory"))
			return true;
		else if(isJDKClass(klass))
			return false;
		else
			return true;
	}

}
