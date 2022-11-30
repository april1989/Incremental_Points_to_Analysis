package edu.tamu.wala.increpta.instancekey;

import com.ibm.wala.classLoader.ArrayClass;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.classLoader.NewSiteReference;
import com.ibm.wala.classLoader.ProgramCounter;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.types.Descriptor;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.types.TypeReference;
import com.ibm.wala.util.debug.Assertions;
import com.ibm.wala.ipa.callgraph.propagation.ConcreteTypeKey;
import com.ibm.wala.ipa.callgraph.propagation.ConstantKey;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKeyFactory;

public class IPAClassBasedInstanceKeys implements InstanceKeyFactory{

	  private final static boolean DEBUG = false;

	  private final AnalysisOptions options;

	  private final IClassHierarchy cha;

	  public IPAClassBasedInstanceKeys(AnalysisOptions options, IClassHierarchy cha) {
	    if (cha == null) {
	      throw new IllegalArgumentException("null cha");
	    }
	    this.cha = cha;
	    this.options = options;
	  }

	  @Override
	  public InstanceKey getInstanceKeyForAllocation(CGNode node, NewSiteReference allocation) {
	    if (allocation == null) {
	      throw new IllegalArgumentException("allocation is null");
	    }

	    if (String.valueOf(allocation).contains("java/lang/invoke/DirectMethodHandle$StaticAccessor")) {
	      System.err.println("got " + allocation + " in " + node);
	    }
	    

	    if (options.getClassTargetSelector() == null) {
	      throw new IllegalStateException("options did not specify class target selector");
	    }
	    IClass type = options.getClassTargetSelector().getAllocatedTarget(node, allocation);
	    if (type == null) {
	      return null;
	    }

	    IPAConcreteTypeKey key = new IPAConcreteTypeKey(type, node.getContext());//

	    return key;
	  }

	  /**
	   * <p>dim == 0 represents the first dimension, e.g., the [Object; instances in [[Object; e.g., the [[Object; instances in
	   * [[[Object;</p>
	   *
	   * <p>dim == 1 represents the second dimension, e.g., the [Object instances in [[[Object;</p>
	   */
	  @Override
	  public InstanceKey getInstanceKeyForMultiNewArray(CGNode node, NewSiteReference allocation, int dim) {
	    if (DEBUG) {
	      System.err.println(("getInstanceKeyForMultiNewArray " + allocation + ' ' + dim));
	    }
	    ArrayClass type = (ArrayClass) options.getClassTargetSelector().getAllocatedTarget(node, allocation);
	    assert (type != null);
	    if (DEBUG) {
	      System.err.println(("type: " + type));
	    }
	    assert type != null : "null type for " + allocation;
	    int i = 0;
	    while (i <= dim) {
	      i++;
	      if (type == null) {
	        Assertions.UNREACHABLE();
	      }
	      type = (ArrayClass) type.getElementClass();
	      if (DEBUG) {
	        System.err.println(("intermediate: " + i + ' ' + type));
	      }
	    }
	    if (DEBUG) {
	      System.err.println(("final type: " + type));
	    }
	    if (type == null) {
	      return null;
	    }
	    //bz: this determines the array element
	    IPAConcreteTypeKey key = new IPAConcreteTypeKey(type, node.getContext());

	    return key;
	  }

	  @Override
	  public <T> InstanceKey getInstanceKeyForConstant(TypeReference type, T S) {
	    if (type == null || cha.lookupClass(type) == null) {
	      return null;//bz: type is primitive, but still need to create instance key
	    }
	    if (options.getUseConstantSpecificKeys()) {
	    	return new ConstantKey<>(S, cha.lookupClass(type));
	    }
	    return new ConcreteTypeKey(cha.lookupClass(type));//bz: not using ipa
	  }

	  /**
	   * @return a set of IPAConcreteTypeKeys that represent the exceptions the PEI may throw.
	   */
	  @Override
	  public InstanceKey getInstanceKeyForPEI(CGNode node, ProgramCounter peiLoc, TypeReference type) {
	    IClass klass = cha.lookupClass(type);
	    if (klass == null) {
	      return null;
	    }
	    return new IPAConcreteTypeKey(cha.lookupClass(type), node.getContext());//
	  }

	  @Override
	  public InstanceKey getInstanceKeyForMetadataObject(Object obj, TypeReference objType) {
	    IClass cls = cha.lookupClass(objType);
	    assert cls != null : objType;
	    
	    if (obj instanceof TypeReference) {
	      IClass klass = cha.lookupClass((TypeReference)obj);
	      if (klass == null) {
	    	  return new ConcreteTypeKey(cls);//bz: reached, but should not be with origin.
	      }
	      // return the IClass itself, wrapped as a constant!
	      return new ConstantKey<>(klass, cls);
	    } else if (obj instanceof MethodReference) {
	    	IMethod m = cha.resolveMethod((MethodReference)obj);
	    	if (m == null) {
	    		return new ConcreteTypeKey(cls);//bz:
	    	}
	    	return new ConstantKey<>(m, cls);
	    } else if (obj instanceof Descriptor) {
	    	return new ConstantKey<>((Descriptor)obj, cls);
	    } else {
	    	// other cases
	    	throw new Error();
	    }
	  }

	  /**
	   * @return Returns the class hierarchy.
	   */
	  public IClassHierarchy getClassHierarchy() {
	    return cha;
	  }


}
