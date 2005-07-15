package iitb.CRF;

import iitb.Utils.StaticObjectHeap;

import java.util.Stack;

import cern.colt.function.*;
import cern.colt.matrix.DoubleMatrix1D;
import cern.colt.matrix.DoubleMatrix2D;
import cern.colt.matrix.impl.*;
/**
 *
 * Viterbi search
 *
 * @author Sunita Sarawagi
 *
 */ 

public class SparseViterbi extends Viterbi {
    protected SparseViterbi(CRF model, int bs) {
        super(model,bs);
    }
    
    protected class Context extends DenseObjectMatrix1D {
        protected int pos;
        protected int beamsize;
       
        protected Context(int numY, int beamsize, int pos){
            super(numY);
            this.pos = pos;
            this.beamsize = beamsize;
        }
        protected Entry newEntry(int beamsize, int label, int pos) {
            return new Entry(beamsize,label,pos);
        }
        public void add(int y, Entry prevEntry, float thisScore) {
            if (getQuick(y) == null) {
                setQuick(y, newEntry((pos==0)?1:beamsize, y, pos));
            }
            getEntry(y).valid = true;
            getEntry(y).add(prevEntry,thisScore);

        }
        public void clear() {
//            assign((Object)null);
            for (int i = 0; i < size(); i++)
                if (getQuick(i) != null)
                    getEntry(i).clear();
        }
        public Entry getEntry(int y) {return (Entry)getQuick(y);}
        /**
         * @param y
         * @return
         */
        public boolean entryNotNull(int y) {
            return ((getQuick(y) != null) && getEntry(y).valid);
        }
        void assign(LogSparseDoubleMatrix1D Ri) {
            for (int y = 0; y < Ri.size(); y++) {
        	  if (Ri.getQuick(y) != 0) 
        	      add(y,null,(float)Ri.get(y));
            }
        }
        public String toString(){
            String toString = "";
            for (int i = 0; i < size(); i++)
                if (getQuick(i) != null)
                    toString += getEntry(i).toString() + " ";                    
            toString += "\n";
            return toString;
        }
    };
    
    protected Context context[];
    protected LogSparseDoubleMatrix1D Ri;
    
    StaticHeapLogSparseDoubleMatrix2D staticHeapMi = null;
    StaticHeapLogSparseDoubleMatrix1D staticHeapRi = null;
    DoubleMatrix2D Mis[][] = null;
    DoubleMatrix1D Ris[][] = null;

    protected BackwardContextUpdate backwardContextUpdate;    
    protected ContextUpdate contextUpdate;    
    
    protected void computeLogMi(DataSequence dataSeq, int i, int ell, double lambda[]) {
        model.featureGenerator.startScanFeaturesAt(dataSeq, i);
        SparseTrainer.computeLogMi(model.featureGenerator,lambda,Mi,Ri);
    }
    
    protected class Iter {
        protected int ell;
        protected void start(int i, DataSequence dataSeq) {ell = 1;}
        protected int nextEll(int i) {return ell--;}
    }
    protected Iter getIter(){return new Iter();}
    protected void finishContext(int i2) {;}
    /**
     * @return
     */
    protected double getCorrectScore(DataSequence dataSeq, int i, int ell) {
        return	(Ri.getQuick(dataSeq.y(i)) + ((i > 0)?Mi.get(dataSeq.y(i-1),dataSeq.y(i)):0));
    }
    protected class ContextUpdate implements IntIntDoubleFunction, IntDoubleFunction {
        protected int i, ell;
        protected Iter iter;
        public double apply(int yp, int yi, double val) {
            if (context[i-ell].entryNotNull(yp))
                context[i].add(yi, context[i-ell].getEntry(yp),(float)(Mi.get(yp,yi)+Ri.get(yi)));
            return val;
        }
        public double apply(int yi, double val) {
            context[i].add(yi,null,(float)Ri.get(yi));
            return val;
        }
        
        double fillArray(DataSequence dataSeq, double lambda[], boolean calcScore) {
            return fillArray(dataSeq, lambda, null, null, -1, calcScore);
        }

        public double fillArray(DataSequence dataSeq, double[] lambda,  
                DoubleMatrix2D[][] Mis, DoubleMatrix1D[][] Ris, 
                 boolean calcScore) {
            return fillArray(dataSeq, lambda, Mis, Ris, -1, calcScore);
        }

        public double fillArray(DataSequence dataSeq, double[] lambda,  
                DoubleMatrix2D[][] Mis, DoubleMatrix1D[][] Ris, 
                Soln soln, boolean calcScore) {
            if(soln == null)
                return fillArray(dataSeq, lambda, Mis, Ris, -1, calcScore);

            for (i = soln.pos; i >= 0; i--)
                context[i].clear();
            
            Stack stack = new Stack();
            while(soln != null){
                stack.push(soln);
                soln = soln.prevSoln;
            }
            int lastPos = -1;
            while(!stack.empty()){
                soln = (Soln)stack.pop();
                switch(soln.prevPos()){
                	case -1:
                        context[soln.pos].add(soln.label,null,(float)soln.score);
                	    break;
                	default:
                        if (context[soln.prevPos()].entryNotNull(soln.prevLabel()))
                            context[soln.pos].add(soln.label, context[soln.prevPos()].getEntry(soln.prevLabel()),soln.score - soln.prevSoln.score);
                	    break;
                }
                
                if(lastPos < soln.pos)
                    lastPos = soln.pos;
            }

            return fillArray(dataSeq, lambda, Mis, Ris, lastPos, calcScore);
        }
        
        private double fillArray(DataSequence dataSeq, double[] lambda, 
                DoubleMatrix2D[][] Mis, DoubleMatrix1D[][] Ris, 
                 int lastPos, boolean calcScore) {
            double corrScore = 0;
            DoubleMatrix1D tempRi = null;
            DoubleMatrix2D tempMi = null;
            if(Mis != null){
	            tempRi = Ri;
	            tempMi = Mi;
            }
            for (i = lastPos + 1; i < dataSeq.length(); i++) {
                context[i].clear();
                for (iter.start(i,dataSeq); (ell = iter.nextEll(i)) > 0;) {
                    // i - ell = i'
                    if(lastPos < 0 || (i - ell) >= lastPos){
                        // compute Mi.
                        if(Mis != null){
	                        Ri = (LogSparseDoubleMatrix1D) Ris[i][ell];
	                        Mi = Mis[i][ell];
                        }else
                            computeLogMi(dataSeq, i, ell, lambda);
	                    if (i - ell < 0) {
	                        Ri.forEachNonZero(this);
	                    } else {
	                        Mi.forEachNonZero(this);                        
	                    }
                    }
                    
                    if (model.params.debugLvl > 1) {
                        System.out.println("Ri :"+Ri);
                        System.out.println("Mi :"+Mi);
                    }
                    
                    if (calcScore) {
                        corrScore += getCorrectScore(dataSeq, i, ell);
                    }
                }	
                finishContext(i);
            }
            /*
            i = dataSeq.length();
            context[i].clear();
            if (i >= 1) {
                for (int yp = 0; yp < context[i-1].size(); yp++) {
                    if (context[i-1].entryNotNull(yp))
                        context[i].add(0, context[i-1].getEntry(yp),0);
                }
            }
            */            
            if(Mis != null){
	            Ri = (LogSparseDoubleMatrix1D) tempRi;
	            Mi = tempMi;
            }
            return corrScore;
        }

    };
    
    class BackwardContextUpdate implements IntIntDoubleFunction, IntDoubleFunction {
        int i, ell;
        Iter iter;
        DataSequence dataSeq;
        Context firstContext;
        
        public double apply(int yp, int yi, double val) {
            if(i == dataSeq.length() - 1){
                context[i+1].add(yi, null, 0);
            }
            if (context[i+1].entryNotNull(yi))
                context[i-ell+1].add(yp, context[i+1].getEntry(yi),(float)(Mi.get(yp,yi)+Ri.get(yi)));
            return val;
        }
        public double apply(int yi, double val) {
            context[0].add(yi,i == dataSeq.length() -1 ? null : context[i+1].getEntry(yi),(float) val);
            return val;
        }

        double fillArray(DataSequence dataSeq, double lambda[], boolean calcScore) {
            return fillArray(dataSeq, lambda, null, null, calcScore);
        }

        double fillArray(DataSequence dataSeq, double lambda[],  
                DoubleMatrix2D Mis[][], DoubleMatrix1D Ris[][], boolean calcScore) {
            this.dataSeq = dataSeq;
            double corrScore = 0;
            DoubleMatrix1D tempRi = null;
            DoubleMatrix2D tempMi = null;
            if(Mis != null){
	            tempRi = Ri;
	            tempMi = Mi;
            }
 
            for (i = dataSeq.length(); i >= 0; i--) {
                context[i].clear();
            }    
            for (i = dataSeq.length() - 1; i >= 0; i--) {
                for (iter.start(i,dataSeq); (ell = iter.nextEll(i)) > 0;) {
                    // compute Mi.
                    // i - ell = i'
                    if(Mis != null){
	                    Mi = Mis[i][ell];                                    
	                    Ri = (LogSparseDoubleMatrix1D) Ris[i][ell];
                    }else
                        computeLogMi(dataSeq, i, ell, lambda);
                    
                    if (i - ell >= 0)
                        Mi.forEachNonZero(this);
                    else
                        Ri.forEachNonZero(this);
                    if (model.params.debugLvl > 1) {
                        System.out.println("Ri "+Ri);
                        System.out.println("Mi "+ Mi);
                    }
                    
                    if (calcScore) {
                        corrScore += getCorrectScore(dataSeq, i, ell);
                    }
                }
                finishContext(i);
            }
            if(Mis != null){
	            Ri = (LogSparseDoubleMatrix1D) tempRi;
	            Mi = tempMi;
            }
            return corrScore;
        }
    };
    
    protected ContextUpdate newContextUpdate() {
        return new ContextUpdate();
    }
    
    protected void allocateScratch(int numY) {
        Mi = new LogSparseDoubleMatrix2D(numY,numY);
        Ri = new LogSparseDoubleMatrix1D(numY);
        context = new Context[0];
        finalSoln = new Entry(beamsize,0,0);
        backwardContextUpdate = new BackwardContextUpdate();
        backwardContextUpdate.iter = getIter();
        contextUpdate = newContextUpdate();
        contextUpdate.iter = getIter();
        allocateStaticHeaps();
    }
    
    void allocateStaticHeaps(){
        staticHeapMi = new StaticHeapLogSparseDoubleMatrix2D(0, model.numY);
        staticHeapRi = new StaticHeapLogSparseDoubleMatrix1D(0, model.numY);        
    }
    
    void allocateContext(int numY, int seqLength){
        Context oldContext[] = context;
        context = new Context[seqLength + 1];
        for (int l = 0; l < oldContext.length; l++) {
            context[l] = oldContext[l];
        }
        for (int l = oldContext.length; l < context.length; l++) {
            context[l] = newContext(numY,beamsize,l);
        }
    }
    
    protected Context newContext(int numY, int beamsize, int pos){
        return new Context(numY,beamsize,pos);        
    }
    
    public double viterbiSearch(DataSequence dataSeq, double lambda[], boolean calcCorrectScore) {
        return viterbiSearch(dataSeq, lambda, null, null, calcCorrectScore);
    }
    
    public double viterbiSearch(DataSequence dataSeq, double lambda[],
            DoubleMatrix2D[][] Mis, DoubleMatrix1D[][] Ris, boolean calScore) {
        initSearch(dataSeq.length());
        double corrScore = contextUpdate.fillArray(dataSeq, lambda,Mis, Ris, calScore);
        if(dataSeq.length() > 0)
            calculatFinalSolution(context[dataSeq.length() - 1]);
        if (model.params.debugLvl > 1) {
            System.out.println("Score of best sequence "+finalSoln.get(0).score + " corrScore " + corrScore);
        }
        return corrScore;
    }                

    public double viterbiSearch(DataSequence dataSeq, double[] lambda,  
            DoubleMatrix2D[][] Mis, DoubleMatrix1D[][] Ris,
            Soln soln, boolean calScore) {
        if(soln == null)
            return viterbiSearch(dataSeq, lambda, Mis, Ris, calScore);
        initSearch(dataSeq.length());
        double corrScore = contextUpdate.fillArray(dataSeq, lambda, Mis, Ris, soln, calScore);
        if(dataSeq.length() > 0)
            calculatFinalSolution(context[dataSeq.length() - 1]);
        if (model.params.debugLvl > 1) {
            System.out.println("Score of best sequence "+finalSoln.get(0).score + " corrScore " + corrScore);
        }
        return corrScore;
    }

    public double viterbiSearchBackward(DataSequence dataSeq, double lambda[], 
            DoubleMatrix2D Mis[][], DoubleMatrix1D Ris[][], boolean calcCorrectScore) {
        initSearch(dataSeq.length());
        double corrScore = backwardContextUpdate.fillArray(dataSeq, lambda, Mis, Ris, calcCorrectScore);
        if(context.length > 0)
            calculatFinalSolution(context[0]);
        if (model.params.debugLvl > 1) {
            System.out.println("Score of best sequence "+finalSoln.get(0).score + " corrScore " + corrScore);
        }
        return corrScore;
    }

    protected void initSearch(int seqLength){
        if (Mi == null)
            allocateScratch(model.numY);
        if(context.length <= seqLength)
            allocateContext(model.numY, seqLength);
        finalSoln.clear();        
    }

    protected void calculatFinalSolution(Context context){
        finalSoln.valid = true;
        for (int y = 0; y < context.size(); y++) {
            if (context.entryNotNull(y))
                finalSoln.add((Entry)context.getQuick(y),0);
        }
    }
    
    void cacheMis(DataSequence dataSeq, double lambda[]){
        if(Mi == null)
            allocateScratch(model.numY);
        allocateCacheArray(dataSeq);
        int ell;
        Iter iter = contextUpdate.iter;
        for (int i = dataSeq.length() - 1; i >= 0; i--) {
            for (iter.start(i,dataSeq); (ell = iter.nextEll(i)) > 0;) {
                // compute Mi.
                // i - ell = i'
                computeLogMi(dataSeq, i, ell, lambda);
                Mis[i][ell].assign(Mi);                                    
                Ris[i][ell].assign(Ri);
            }
        }            
    }

    private void allocateCacheArray(DataSequence dataSeq) {
        int i = -1, seqLength = dataSeq.length(), ell;
        Iter iter = getIter();
        Mis = new LogSparseDoubleMatrix2D[seqLength][];
        Ris = new LogSparseDoubleMatrix1D[seqLength][];
        int size = 0, maxEll = 0;
        staticHeapMi.reset();
        staticHeapRi.reset();
        for(i++; i < seqLength; i++){
            iter.start(i, dataSeq);
            while((ell = iter.nextEll(i)) > 0 )
                maxEll = (maxEll < ell) ? ell : maxEll;
            size = (i < maxEll ? i+1: maxEll);
            Mis[i] = new LogSparseDoubleMatrix2D[size + 1];
            Ris[i] = new LogSparseDoubleMatrix1D[size + 1];            
            for(int j = 0; j < Mis[i].length; j++){
                Mis[i][j] =  (DoubleMatrix2D) staticHeapMi.getObject(); //new LogSparseDoubleMatrix2D(numY,numY);
                Ris[i][j] =  (DoubleMatrix1D) staticHeapRi.getObject(); //new LogSparseDoubleMatrix1D(numY);
            }
        }
    }

    class StaticHeapLogSparseDoubleMatrix2D extends StaticObjectHeap{
        int numY;
        public StaticHeapLogSparseDoubleMatrix2D(int initCapacity, int numY) {
            super(initCapacity);
            this.numY = numY;
        }

        protected Object getObject() {
            return getFreeObject();
        }        

        protected Object newObject() {
            return new LogSparseDoubleMatrix2D(numY,numY);
        }        
    }
    
    class StaticHeapLogSparseDoubleMatrix1D extends StaticObjectHeap{
        int numY;
        public StaticHeapLogSparseDoubleMatrix1D(int initCapacity, int numY) {
            super(initCapacity);
            this.numY = numY;
        }
        
        protected Object newObject() {
            return new LogSparseDoubleMatrix1D(numY);
        }

        protected Object getObject() {
            return getFreeObject();
        }        
    }
    
    public DoubleMatrix2D[][] getMis() {
        return Mis;
    }
    
    public DoubleMatrix1D[][] getRis() {
        return Ris;
    }
};
