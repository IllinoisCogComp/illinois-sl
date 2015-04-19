package edu.illinois.cs.cogcomp.sl.applications.depparse;

import edu.illinois.cs.cogcomp.sl.applications.depparse.base.DependencyInstance;
import edu.illinois.cs.cogcomp.sl.core.IInstance;
import edu.illinois.cs.cogcomp.sl.util.SparseFeatureVector;

public class DepInst implements IInstance {

	public String[] lemmas;
	public String[] pos;
	public String[] cpos;

	public DepInst(DependencyInstance instance) {
		lemmas = instance.lemmas;
		pos = instance.postags;
		cpos = instance.cpostags;
	}

	/***
	 * # of tokens in the sentence
	 * @return
	 */
	public int size() {
		return lemmas.length - 1; // this is the true size, after removing the 0
									// root
	}

}