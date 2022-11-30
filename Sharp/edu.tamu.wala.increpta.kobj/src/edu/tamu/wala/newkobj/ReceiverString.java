package edu.tamu.wala.newkobj;


import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.classLoader.NewSiteReference;
import com.ibm.wala.ipa.callgraph.ContextItem;




/**
 * @author Bozhen
 * 
 * similar to com.ibm.wala.ipa.callgraph.propagation.cfa.CallString, 
 * use NewSiteReference[] and IMethod[] to replace InstanceKey[]
 */
public class ReceiverString implements ContextItem {

	private final NewSiteReference sites[];

	private final IMethod methods[];

	public ReceiverString(NewSiteReference site, IMethod method) {
		if (site == null) {
			throw new IllegalArgumentException("null site");
		}
		this.sites = new NewSiteReference[] { site };
		this.methods = new IMethod[] { method };
	}

	ReceiverString(NewSiteReference site, IMethod method, int length, ReceiverString base) {
		int sitesLength = Math.min(length, base.sites.length + 1);
		int methodsLength = Math.min(length, base.methods.length + 1);
		sites = new NewSiteReference[sitesLength];
		sites[0] = site;
		System.arraycopy(base.sites, 0, sites, 1, Math.min(length - 1, base.sites.length));
		methods = new IMethod[methodsLength];
		methods[0] = method;
		System.arraycopy(base.methods, 0, methods, 1, Math.min(length - 1, base.methods.length));
	}

	private int getCurrentLength() {
		return sites.length;
	}
	
	public IMethod[] getMethods() {
		return methods;
	}

	public NewSiteReference[] getNewSites() {
		return sites;
	}

	@Override
	public String toString() {
		StringBuilder str = new StringBuilder("[");
		for (int i = 0; i < sites.length; i++) {
			str.append(' ').append(sites[i].getDeclaredType()).append('@').append(sites[i].getProgramCounter())
			.append("::").append(methods[i].getSignature());
		}
		str.append(" ]");
		return str.toString();
	}

	@Override
	public int hashCode() {
		int code = 1;
		for (int i = 0; i < sites.length; i++) {
			code *= sites[i].hashCode() * methods[i].hashCode();
		}

		return code;
	}

	@Override
	public boolean equals(Object o) {
		if (o instanceof ReceiverString) {
			ReceiverString oc = (ReceiverString) o;
			if (oc.sites.length == sites.length) {
				for (int i = 0; i < sites.length; i++) {
					if (!(sites[i].equals(oc.sites[i]) && methods[i].equals(oc.methods[i]))) {
						return false;
					}
				}

				return true;
			}
		}

		return false;
	}

	/**
	 * for Sctx and Sinv
	 * @param o_site -> other 
	 * @param o_method -> other
	 * @return yes or no
	 */
	public boolean includes(NewSiteReference o_site, IMethod o_method) {
		int size = sites.length;
		for(int i = 0; i < size; i++) {
			if(this.sites[i].equals(o_site) && this.methods[i].equals(o_method)) {
				return true;
			}
		}
		return false;
	}

}

