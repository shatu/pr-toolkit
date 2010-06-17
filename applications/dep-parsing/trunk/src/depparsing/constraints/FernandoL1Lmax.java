package depparsing.constraints;

import static util.ArrayMath.deepclone;
import constraints.CorpusConstraints;

import gnu.trove.TIntArrayList;
import gnu.trove.TIntObjectHashMap;
import gnu.trove.TIntObjectIterator;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;

import optimization.gradientBasedMethods.ProjectedGradientDescent;
import optimization.gradientBasedMethods.stats.ProjectedOptimizerStats;
import optimization.linesearch.GenericPickFirstStep;
import optimization.linesearch.LineSearchMethod;
import optimization.linesearch.WolfRuleLineSearch;
import optimization.stopCriteria.CompositeStopingCriteria;
import optimization.stopCriteria.NormalizedProjectedGradientL2Norm;
import optimization.stopCriteria.NormalizedValueDifference;
import optimization.stopCriteria.StopingCriteria;

import learning.CorpusPR;
import learning.stats.TrainStats;
import model.AbstractCountTable;
import model.AbstractSentenceDist;
import util.Alphabet;
import util.MemoryTracker;


import data.WordInstance;
import depparsing.constraints.L1Lmax.PCType;
import depparsing.data.DepCorpus;
import depparsing.data.DepInstance;
import depparsing.learning.stats.L1LMaxStats;
import depparsing.model.DepModel;
import depparsing.model.DepSentenceDist;

/**
 * 
 * @author kuzman
 * 
 * This represents the dual objective to the L1Lmax penalty.  The primal 
 * objective is:
 *     min   KL(q||p) + \sum_cp Xi_cp
 *     s.t.  Xi_cp <= E_q[f_cpi] for all c,p,i
 * where c is the child item, p is the parent item and i is the index. 
 * For example, to enforce the constraint "each child word is generated by few
 * parent POS tags" you would need c to range over all words, p to range over
 * all POS tags, and there would be a separate i for each possible edge that
 * has child word c and parent POS p. 
 * 
 * The sentence distributions have edges arranged by sentences in the corpus,
 * but in order to do the simplex projection we need to be able to arrange 
 * them in order of child-type,parent-type,index.  This class stores information 
 * about how to do the reshaping of the parameters when we need to apply the 
 * 
 * 
 */
public class FernandoL1Lmax implements CorpusConstraints {
	

	public static class ConstraintEnumerator{
		private final PCType childType, parentType;
		public final boolean useRoot;
		public final boolean useDirection;
		private final DepCorpus c;
		private final Alphabet<String> types2indices;
		TIntArrayList[] parentIdsPerChild;
		TIntArrayList edge2childType;
		TIntArrayList edge2parentType;
		
		public ConstraintEnumerator(DepCorpus c, PCType childType, PCType parentType, boolean useRoot, boolean useDirection){
			this.c = c;
			this.childType = childType;
			this.parentType = parentType;
			this.useRoot = useRoot;
			this.useDirection = useDirection;
			this.types2indices = new Alphabet<String>();
			parentIdsPerChild = new TIntArrayList[numIdsChild()];
			edge2childType = new TIntArrayList();
			edge2parentType = new TIntArrayList();
			for (int child = 0; child < parentIdsPerChild.length; child++) {
				parentIdsPerChild[child] = new TIntArrayList();
			}
		}
		
		public int root2cid(DepInstance di, int rootIndex){
			if(!useRoot) return -1; 
			int childId = index2id(di, rootIndex, getChildType());
			String childName = getChildType().id2string(c, childId);
			int res = types2indices.lookupObject("root="+childName);
			if (edge2childType.size() <= res){
				assert edge2childType.size() == res && edge2parentType.size() == res;
				edge2childType.add(-2);
				edge2parentType.add(-2);
				edge2childType.set(res, childId);
				edge2parentType.set(res, -1);
			}
			return res;
		}
				
		public int edge2cid(DepInstance di, int child, int parent){
			String dir = "";
			if (useDirection) dir = child>parent? "right":"left";
			int childId = index2id(di,child,getChildType());
			int parentId = index2id(di,parent,getParentType());
			String childName = getChildType().id2string(c, childId);
			String parentName = getParentType().id2string(c, parentId);
			int res=types2indices.lookupObject("edge="+childName+","+parentName+":"+dir);
			if (!parentIdsPerChild[childId].contains(res)) parentIdsPerChild[childId].add(res);
			if (edge2childType.size() <= res){
				assert edge2childType.size() == res && edge2parentType.size() == res;
				edge2childType.add(-2);
				edge2parentType.add(-2);
				edge2childType.set(res, childId);
				edge2parentType.set(res, parentId);
			}
			return res;
		}
		
		/**
		 * Only gets the id of the edge if it's been observed before; otherwise it returns -1. 
		 * @param child
		 * @param parent
		 * @param dir should be "right" or "left"
		 * @return
		 */
		public int getEdgeId(String child, String parent, String dir){
			if (!useDirection) dir = "";
			String edgeName = "edge="+child+","+parent+":"+dir;
			if (!types2indices.feat2index.contains(edgeName)) return -1;
			return types2indices.lookupObject(edgeName);
		}
		
		/** 
		 * @return a list of indices corresponding to different edge types for each child type.
		 * used by the stats class {@code L1LMaxStats}. 
		 */
		public TIntArrayList[] cidAsMatrix(){
			return parentIdsPerChild;
		}

		public String constraint2string(int c){
			return types2indices.lookupIndex(c);
		}
		
		private int numIds(PCType t){
			switch (t) {
			case WORD: return c.getNrWordTypes();
			case TAG: return c.getNrTags();
			}
			throw new RuntimeException("unknwon tag type");
		}
		
		private int numIdsChild(){
			return useDirection? numIds(getChildType()) : numIds(getChildType())*2;
		}
		
		private int numIdsParent(){
			return useRoot? numIds(getParentType()) : numIds(getParentType())+1;
		}

		private int index2id(DepInstance di, int ind, PCType t){
			switch (t) {
			case WORD: return  di.words[ind];
			case TAG: return di.postags[ind];
			}
			throw new RuntimeException("unknwon tag type");
		}
		
		public PCType getChildType() {
			return childType;
		}

		public PCType getParentType() {
			return parentType;
		}
		
		public int getChildType(int edgeType){
			return edge2childType.get(edgeType);
		}
		
		public int getParentType(int edgeType){
			return edge2parentType.get(edgeType);
		}
	};
		
	final int numChildIds;  // number of types of children (e.g. number of words) 
	final int numParentIds; // number of types of parent (e.g. number of tags)
	final ConstraintEnumerator cstraints;
	final DepCorpus corpus;
	final DepModel model;
	
	/** 
	 * in order to avoid re-allocating lambda, we store it here. Similarly for 
	 * paramsOfP
	 */
	double[] lambda;
	double[][][][] originalChildren;
	double[][] originalRoots;
	
	/** We're going to store the mapping from the sentence, child token index, parent token index to and from 
	 * child type, parent type, edge index in the scp2cpi and cpi2scp arrays.  That way reshaping should not 
	 * require any counting. */
	class SentenceChildParent {public int s,c; public int[] parents; public SentenceChildParent(int s2, int c2, int[] p2){s=s2;c=c2;parents=p2; if(parents == null) throw new AssertionError("parents is null");}}
	final SentenceChildParent[][] edge2scp;  // indexed by type, index
	private final double constraintStrength;
	private TIntArrayList edgesToNotProject;
	// Debugging code -- to make sure we're doing everything correctly in terms of counting. 
	SentenceChildParent[] param2scp;
	
	final double c1= 0.0001, c2=0.9, stoppingPrecision = 1e-5, maxStep = 10;
	final int maxZoomEvals = 10, maxExtrapolationIters = 200;
	int maxProjectionIterations = 200;
	int minOccurrencesForProjection = 0;
	
	public FernandoL1Lmax(DepCorpus corpus, DepModel model, ArrayList<WordInstance> toProject, PCType cType, PCType pType, 
			boolean useRoot, boolean useDirection, double constraintStrength, int minOccurrencesForProjection, String fileOfAllowedTypes) throws IOException{
		this.corpus = corpus;
		this.model = model;
		this.cstraints = new ConstraintEnumerator(corpus, cType, pType, useRoot, useDirection);
		this.constraintStrength = constraintStrength;
		this.minOccurrencesForProjection = minOccurrencesForProjection;
		numChildIds = cstraints.numIdsChild();
		numParentIds = cstraints.numIdsParent();
		ArrayList<Integer> indicesforcp = new ArrayList<Integer>();

		// compute how many of each childType-parentType pair there are. 
		for (int s = 0; s < toProject.size(); s++) {
			DepInstance di = (DepInstance) toProject.get(s);
			for (int childIndex = 0; childIndex < di.numWords; childIndex++) {
				int roottype = cstraints.root2cid(di, childIndex);
				if (roottype >= 0){ 
					while (roottype >=indicesforcp.size()) indicesforcp.add(0);
					indicesforcp.set(roottype, 1+indicesforcp.get(roottype));
				}
				TIntObjectHashMap<TIntArrayList> parsByEdgeType = new TIntObjectHashMap<TIntArrayList>();
				for (int parentIndex = 0; parentIndex < di.numWords; parentIndex++) {
					int edgetype = cstraints.edge2cid(di,childIndex, parentIndex);
					if (!parsByEdgeType.contains(edgetype)) parsByEdgeType.put(edgetype, new TIntArrayList());
					parsByEdgeType.get(edgetype).add(parentIndex);
				}
				for (TIntObjectIterator<TIntArrayList> itr = parsByEdgeType.iterator(); itr.hasNext();) {
					itr.advance();
					int edgetype = itr.key();
					while (edgetype >=indicesforcp.size()) indicesforcp.add(0);
					indicesforcp.set(edgetype, 1+indicesforcp.get(edgetype));
				}
			}
		}
		
		int numParams = 0;

		// count how many edge types will not be projected for reporting
		int notToProject = 0;
		// create arrays..
		edge2scp = new SentenceChildParent[indicesforcp.size()][];
		for (int i = 0; i < edge2scp.length; i++) {
			edge2scp[i] = new SentenceChildParent[indicesforcp.get(i)];
			numParams += indicesforcp.get(i);
			if (minOccurrencesForProjection > edge2scp[i].length){
				notToProject +=1;
			}
			indicesforcp.set(i,0);
		}
		int totalEdgeTypes = indicesforcp.size();
		System.out.println("Will project "+(totalEdgeTypes-notToProject)+" / "+totalEdgeTypes+" the rest fall below min occurrences to project");
		
		// fill in the matrices
		for (int s = 0; s < toProject.size(); s++) {
			DepInstance di = (DepInstance) toProject.get(s);
			for (int childIndex = 0; childIndex < di.numWords; childIndex++) {
				int roottype = cstraints.root2cid(di, childIndex);
				if (roottype >= 0) {
					int index = indicesforcp.get(roottype);
					edge2scp[roottype][index] = new SentenceChildParent(s,childIndex,new int[] {-1});
					indicesforcp.set(roottype, 1+index);
				}
				TIntObjectHashMap<TIntArrayList> parsByEdgeType = new TIntObjectHashMap<TIntArrayList>();
				for (int parentIndex = 0; parentIndex < di.numWords; parentIndex++) {
					int edgetype = cstraints.edge2cid(di,childIndex, parentIndex);
					if (!parsByEdgeType.contains(edgetype)) parsByEdgeType.put(edgetype, new TIntArrayList());
					parsByEdgeType.get(edgetype).add(parentIndex);
				}
				for (TIntObjectIterator<TIntArrayList> itr = parsByEdgeType.iterator(); itr.hasNext();) {
					itr.advance();
					int edgetype = itr.key();
					TIntArrayList parents = itr.value();
					int index = indicesforcp.get(edgetype);
					edge2scp[edgetype][index] = new SentenceChildParent(s,childIndex,parents.toNativeArray());
					indicesforcp.set(edgetype, 1+index);
				}
			}
		}
		
		// create the param2scp 
		param2scp = new SentenceChildParent[numParams];
		int paramIndex = 0;
		for (int edgeType = 0; edgeType < edge2scp.length; edgeType++) {
			for (int index = 0; index < edge2scp[edgeType].length; index++) {
				param2scp[paramIndex++] = edge2scp[edgeType][index];
			}
		}
		// FIXME: edgesToNotProject has not been used for a while; do we want to keep it?
		if (fileOfAllowedTypes != null)
			edgesToNotProject = makeEdgesToNotProject(fileOfAllowedTypes);
		else {
			edgesToNotProject = new TIntArrayList();
		}
	}
	
	private TIntArrayList makeEdgesToNotProject(String fname) throws IOException{
		TIntArrayList res = new TIntArrayList();
		BufferedReader in = new BufferedReader(new FileReader(fname));
		for (String ln = in.readLine(); ln!= null; ln=in.readLine()){
			ln = ln.replaceAll("#.*", "");
			ln = ln.replaceAll(" *$", "");
			if (ln.length() == 0) continue;
			String par = ln.split("  *")[0];
			String child = ln.split("  *")[1];
			int edgeType = cstraints.getEdgeId(child, par, "left");
			if (edgeType < 0) System.out.println("Edge "+par+" -> "+child+" : left doesn't seem to exist, hope that's OK");
			if(!res.contains(edgeType)) res.add(edgeType);
			edgeType = cstraints.getEdgeId(child, par, "right");
			if (edgeType < 0) System.out.println("Edge "+par+" -> "+child+" : right doesn't seem to exist, hope that's OK");
			if(!res.contains(edgeType)) res.add(edgeType);
		}
		in.close();
		return res;
	}
	
	public double getConstraintStrength(int edgeType){
		double myCstrength = this.constraintStrength;
		if (edgesToNotProject.contains(edgeType)) return 0;
		// min occurrences for projection.. FIXME: this didn't help performance, and should be deleted
		if (minOccurrencesForProjection > edge2scp[edgeType].length){
			myCstrength = 0;
		}
		return myCstrength;
	}
	
	@SuppressWarnings("unchecked")
	public void project(AbstractCountTable counts,
			AbstractSentenceDist[] posteriors, TrainStats trainStats, CorpusPR pr) {
		MemoryTracker mem  = new MemoryTracker();
		mem.start();
		trainStats.eStepStart(model, pr);
		int numParams = 0;
		for (int i = 0; i < edge2scp.length; i++) {
			numParams += edge2scp[i].length;
		}
		if (numParams!= param2scp.length) throw new AssertionError();
		if (lambda == null){
			lambda = new double[numParams];
			originalChildren = new double[posteriors.length][][][];
			originalRoots = new double[posteriors.length][];
		}
		// FIXME: figure out a way to check that sentences have not changed!
//		if (lambda.value.length != posteriors.length) throw new RuntimeException("num sentences changed!");
//		for (int i = 0; i < posteriors.length; i++) {
//			if (lambda.value[i].length != posteriors[i].depInst.numWords) throw new RuntimeException("sentence "+i+" length changed!");			
//		}
		for (int s = 0; s < posteriors.length; s++) {
			DepSentenceDist sd = (DepSentenceDist) posteriors[s];
			sd.cacheModelAndComputeIO(model.params);
		}
		for (int s = 0; s < posteriors.length; s++) {
			originalChildren[s] =  deepclone(((DepSentenceDist)posteriors[s]).child);
			originalRoots[s] = ((DepSentenceDist)posteriors[s]).root.clone();
		}
		ProjectedOptimizerStats stats = new ProjectedOptimizerStats();
		FernandoL1LMaxObjective objective = new FernandoL1LMaxObjective(lambda, this, posteriors);
		// objective.doTestGradient = true;
		GenericPickFirstStep pickFirstStep = new GenericPickFirstStep(1);
		LineSearchMethod linesearch = new WolfRuleLineSearch(pickFirstStep, c1, c2);
		ProjectedGradientDescent optimizer = new ProjectedGradientDescent(linesearch);
		optimizer.setMaxIterations(maxProjectionIterations);
//		GradientAscentProjection optimizer = new GradientAscentProjection(linesearch,stoppingPrecision, maxProjectionIterations);
        StopingCriteria stopGrad = new NormalizedProjectedGradientL2Norm(stoppingPrecision);
        StopingCriteria stopValue = new NormalizedValueDifference(stoppingPrecision);
        CompositeStopingCriteria stop = new CompositeStopingCriteria();
        stop.add(stopGrad);
        stop.add(stopValue);
        boolean succed = optimizer.optimize(objective, stats,stop);
		// make sure we update the dual params
		objective.getValue();
		counts.clear();
		for (int i = 0; i < posteriors.length; i++) {
			model.addToCounts(posteriors[i], counts);
		}
		mem.finish();
		System.out.println("After  optimization:" + mem.print());
		System.out.println("Suceess " + succed + "/n"+stats.prettyPrint(1));
	}

	public void setMaxProjectionSteps(int tmpProjectItersAtPool) {
		maxProjectionIterations = tmpProjectItersAtPool;
	}

}
