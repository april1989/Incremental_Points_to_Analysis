package edu.tamu.wala.increpta.callgraph.impl;

import java.util.Iterator;

import com.ibm.wala.classLoader.CallSiteReference;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.classLoader.NewSiteReference;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.Context;
import com.ibm.wala.ipa.callgraph.ContextItem;
import com.ibm.wala.ipa.cha.IClassHierarchyDweller;
import com.ibm.wala.ssa.DefUse;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.util.graph.INodeWithNumber;


public interface IPACGNode extends CGNode {

	//bz
	public boolean delTarget(CallSiteReference callSite, CGNode target);

}
