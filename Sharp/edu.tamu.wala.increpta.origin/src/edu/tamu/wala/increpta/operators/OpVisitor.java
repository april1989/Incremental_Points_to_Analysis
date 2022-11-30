package edu.tamu.wala.increpta.operators;

import edu.tamu.wala.increpta.ipa.callgraph.propagation.IPAPointsToSetVariable;
import edu.tamu.wala.increpta.ipa.callgraph.propagation.IPAPropagationCallGraphBuilder.ArrayLoadOperator;
import edu.tamu.wala.increpta.ipa.callgraph.propagation.IPAPropagationCallGraphBuilder.ArrayStoreOperator;
import edu.tamu.wala.increpta.ipa.callgraph.propagation.IPAPropagationCallGraphBuilder.GetFieldOperator;
import edu.tamu.wala.increpta.ipa.callgraph.propagation.IPAPropagationCallGraphBuilder.IPAFilterOperator;
import edu.tamu.wala.increpta.ipa.callgraph.propagation.IPAPropagationCallGraphBuilder.InstanceArrayStoreOperator;
import edu.tamu.wala.increpta.ipa.callgraph.propagation.IPAPropagationCallGraphBuilder.InstancePutFieldOperator;
import edu.tamu.wala.increpta.ipa.callgraph.propagation.IPAPropagationCallGraphBuilder.PutFieldOperator;
import edu.tamu.wala.increpta.ipa.callgraph.propagation.IPASSAPropagationCallGraphBuilder.IPADispatchOperator;

public abstract class OpVisitor {

	public void translateAssign(IPAUnaryStatement<IPAPointsToSetVariable> stmt) {
	}

	public void translateFilter(IPAFilterOperator op, IPAUnaryStatement<IPAPointsToSetVariable> stmt) {
	}

	public void translateInstanceArrayStore(InstanceArrayStoreOperator op, IPAUnaryStatement<IPAPointsToSetVariable> stmt) {
	}

	public void translateInstancePutField(InstancePutFieldOperator op, IPAUnaryStatement<IPAPointsToSetVariable> stmt) {
	}

	public void translateArrayLoad(ArrayLoadOperator op, IPAUnaryStatement<IPAPointsToSetVariable> stmt) {
	}

	public void translateArrayStore(ArrayStoreOperator op, IPAUnaryStatement<IPAPointsToSetVariable> stmt) {
	}

	public void translateGetField(GetFieldOperator op, IPAUnaryStatement<IPAPointsToSetVariable> stmt) {
	}

	public void translatePutField(PutFieldOperator op, IPAUnaryStatement<IPAPointsToSetVariable> stmt) {
	}

	public void translateDispatch(IPADispatchOperator op, IPAGeneralStatement<IPAPointsToSetVariable> stmt) {
	}

}
