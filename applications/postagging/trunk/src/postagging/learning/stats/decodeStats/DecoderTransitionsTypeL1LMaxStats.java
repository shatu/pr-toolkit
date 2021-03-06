package postagging.learning.stats.decodeStats;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;

import optimization.gradientBasedMethods.Objective;
import optimization.gradientBasedMethods.Optimizer;

import data.Corpus;
import decoderStats.AbstractDecoderStats;
import postagging.data.PosCorpus;
import postagging.model.PosHMM;
import util.ArrayMath;
import util.InputOutput;
import util.Printing;
import gnu.trove.TIntDoubleHashMap;
import learning.EM;
import learning.stats.TrainStats;
import model.AbstractModel;
import model.AbstractSentenceDist;
import model.chain.hmm.HMM;
import model.chain.hmm.HMMDirectGradientObjective;
import model.chain.hmm.HMMSentenceDist;
import model.chain.hmm.directGradientStats.MultinomialMaxEntDirectTrainerStats;


/**
 * Computes the L1LMax stats at the end of each iteration
 * @author javg
 *
 */
public class DecoderTransitionsTypeL1LMaxStats extends AbstractDecoderStats{

		int nrHiddenStates;
		double[][] maxTable;
		double[][] l2Table;
	
		
		PosCorpus c;
		String[] allTags;
        public DecoderTransitionsTypeL1LMaxStats(PosCorpus c, HMM model) {
        	this.c = c;
        	this.nrHiddenStates = model.getNrRealStates();
			maxTable = new double[nrHiddenStates][nrHiddenStates];
			l2Table = new double[nrHiddenStates][nrHiddenStates];
			String[] tagNames = c.getAllTagsStrings();
			//Add final tag state
			allTags = new String[tagNames.length+1];
			System.arraycopy(tagNames, 0, allTags, 0, tagNames.length);
			allTags[tagNames.length]="last";
        }
        
        //Clears the counts tables
        public void beforeInference(HMM model){
        		for (int i = 0; i < nrHiddenStates; i++) {
        			java.util.Arrays.fill(maxTable[i],0);
        			java.util.Arrays.fill(l2Table[i],0);
        		}
        }
        
        //Change the maxes for this particular sentence
    	public void afterSentenceInference(HMM model, AbstractSentenceDist sd){
        		HMMSentenceDist dist = (HMMSentenceDist)sd;
        		for (int pos = 0; pos < dist.getNumberOfPositions()-1; pos++) {
						for (int prevState = 0; prevState < nrHiddenStates; prevState++) {
							for (int nextState = 0; nextState < nrHiddenStates; nextState++) {
								double prob = dist.getTransitionPosterior(pos, prevState, nextState);
								if(maxTable[prevState][nextState] < prob){
									maxTable[prevState][nextState] = prob;
								}
								l2Table[prevState][nextState] += prob*prob;
							}
						}
					}
				}
        
    	
    	public String collectFinalStats(HMM model) {
    		double totals[] = new double[nrHiddenStates];
    		double l2totals[] = new double[nrHiddenStates];
    		for (int i = 0; i < nrHiddenStates; i++) {		
    			for (int j = 0; j < nrHiddenStates; j++) {
    				totals[i] += maxTable[i][j];
    				l2totals[i] +=l2Table[i][j];
    			}
    			l2totals[i] = Math.sqrt(l2totals[i])/nrHiddenStates;
    			totals[i]/=nrHiddenStates;
    		}
            
    		double totalL1LMax = ArrayMath.sum(totals);
    		double totalL1L2 = ArrayMath.sum(l2totals);
            	            		            
    		return "L1LMax " + totalL1LMax + " AVG " + totalL1LMax/nrHiddenStates 
            	     + " L1LL2 " + totalL1L2 + " AVG " + totalL1L2/nrHiddenStates;
     }
        
     
        @Override
        public String getPrefix() {
            return "TransL1LMax::";
        }

		@Override
		public void endInference(HMM model) {
			// TODO Auto-generated method stub
			
		}

        
        
       
        
        
    
}