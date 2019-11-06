package pitt.search.semanticvectors;

import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;

import pitt.search.semanticvectors.LuceneUtils.TermWeight;
import pitt.search.semanticvectors.utils.VerbatimLogger;
import pitt.search.semanticvectors.vectors.RealVector;
import pitt.search.semanticvectors.vectors.Vector;
import pitt.search.semanticvectors.vectors.VectorType;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.logging.Logger;

import ch.akuhn.edu.mit.tedlab.*;

/**
 * Interface to Adrian Kuhn and David Erni's implementation of SVDLIBJ, a native Java version
 * of Doug Rhodes' SVDLIBC, which was in turn based on SVDPACK, by Michael Berry, Theresa Do,
 * Gavin O'Brien, Vijay Krishna and Sowmini Varadhan.
 *
 * This class will produce two files, svd_termvectors.bin and svd_docvectors.bin from a Lucene index
 * Command line arguments are consistent with the rest of the Semantic Vectors Package
 */
public class LSA {
  private static final Logger logger = Logger.getLogger(LSA.class.getCanonicalName());

  public static String usageMessage = "\nLSA class in package pitt.search.semanticvectors"
      + "\nUsage: java pitt.search.semanticvectors.LSA [other flags] -luceneindexpath PATH_TO_LUCENE_INDEX"
      + "Use flags to configure dimension, min term frequency, etc. See online documentation for other available flags";

  private FlagConfig flagConfig;
  /** Stores the list of terms in the same order as rows in the matrix. */
  private String[] termList;
  /** The single contents field in the Lucene index: LSA only supports one. */
  private String contentsField;
  private LuceneUtils luceneUtils;
  
  /**
   * Basic constructor that tries to check up front that resources are available and
   * configurations are consistent.
   * 
   * @param luceneIndexDir Relative path to directory containing Lucene index.
   * @throws IOException 
   */
  private LSA(String luceneIndexDir, FlagConfig flagConfig) throws IOException {
    this.flagConfig = flagConfig;    
    this.luceneUtils = new LuceneUtils(flagConfig);

    if (flagConfig.contentsfields().length > 1) {
      logger.warning(
          "LSA implementation only supports a single -contentsfield. Only '"
          + flagConfig.contentsfields()[0] + "' will be indexed.");
    }

    this.contentsField = flagConfig.contentsfields()[0];
    
    // Find the number of docs, and if greater than dimension, set the dimension
    // to be this number.
    if (flagConfig.dimension() > this.luceneUtils.getNumDocs()) {
      logger.warning("Dimension for SVD cannot be greater than number of documents ... "
          + "Setting dimension to " + this.luceneUtils.getNumDocs());
      flagConfig.setDimension(this.luceneUtils.getNumDocs());
    }

    if (flagConfig.termweight().equals(TermWeight.LOGENTROPY)) {
      VerbatimLogger.info("Term weighting: log-entropy.\n");
    }

    // Log some of the basic properties. This could be altered to be more informative if
    // our users ever ask for different properties.
    VerbatimLogger.info("Set up LSA indexer.\n" +
    		"Dimension: " + flagConfig.dimension() + " Lucene index contents field: '" + this.contentsField + "'"
        + " Minimum frequency = " + flagConfig.minfrequency()
        + " Maximum frequency = " + flagConfig.maxfrequency()
        + " Number non-alphabet characters = " + flagConfig.maxnonalphabetchars() +  "\n");
  }

  /**
   * Converts the Lucene index into a sparse matrix.
   * Also populates termList as a side-effect.
   * 
   * @returns sparse term-document matrix in the format expected by SVD library
   */
  private SMat smatFromIndex() throws IOException {
    SMat S;
    Iterator<String> terms = this.luceneUtils.getTermsForField(contentsField);
    int numTerms = 0,   nonZeroVals = 0;
    
    //count number of terms meeting term filter constraints, and number of nonzero matrix entries  
    while (terms.hasNext()) {
      Term term = new Term(contentsField, terms.next());
      if (this.luceneUtils.termFilter(term))
        numTerms++;

      ArrayList<PostingsEnum> allDocsEnum = this.luceneUtils.getDocsForTerm(term);
      for (PostingsEnum docsEnum:allDocsEnum)
    	  	while (docsEnum.nextDoc() != PostingsEnum.NO_MORE_DOCS) 
    	  		++nonZeroVals;
    }

    VerbatimLogger.info(String.format(
        "There are %d terms (and %d docs).\n", numTerms, this.luceneUtils.getNumDocs()));

    termList = new String[numTerms];
  
      // Initialize "SVDLIBJ" sparse data structure.
    S = new SMat(this.luceneUtils.getNumDocs(), numTerms, nonZeroVals);

    // Populate "SVDLIBJ" sparse data structure and list of terms.
    int termCounter = 0;
    int firstNonZero = 0; // Index of first non-zero entry (document) of each column (term).
    
    while (terms.hasNext()) {
      Term term = new Term(contentsField, terms.next());
      
      if (this.luceneUtils.termFilter(term)) {
          S.pointr[termCounter] = firstNonZero; //index of first non-zero entry
          termList[termCounter] = term.text();

        ArrayList<PostingsEnum> allDocsEnum = this.luceneUtils.getDocsForTerm(term);
        
        for (PostingsEnum docsEnum:allDocsEnum)
        		while (docsEnum.nextDoc() != PostingsEnum.NO_MORE_DOCS) {
          /** public int[] pointr; For each col (plus 1), index of
            *  first non-zero entry.  we'll represent the matrix as a
            *  document x term matrix such that terms are columns
            *  (otherwise it would be difficult to extract this
            *  information from the lucene index)
            */
        	
        			S.rowind[firstNonZero] = docsEnum.docID();  // set row index to document number
        			float value 	= luceneUtils.getGlobalTermWeight(term); //global weight
        			value 		=  value * (float) luceneUtils.getLocalTermWeight(docsEnum.freq()); // multiply by local weight
        			S.value[firstNonZero] = value;  // set value to frequency (with/without weighting)
        			firstNonZero++;
        }
        termCounter++;
      }
    }
    S.pointr[S.cols] = S.vals;

    return S;
  }

  private void writeOutput(DMat vT, DMat uT) throws IOException {
    // Open file and write headers.
    FSDirectory fsDirectory = FSDirectory.open(FileSystems.getDefault().getPath("."));
    IndexOutput outputStream = fsDirectory.createOutput(
        VectorStoreUtils.getStoreFileName(flagConfig.termvectorsfile(), flagConfig),
        IOContext.DEFAULT);
  
    // Write header giving number of dimensions for all vectors and make sure type is real.
    outputStream.writeString(VectorStoreWriter.generateHeaderString(flagConfig));
    int cnt;
    // Write out term vectors
    for (cnt = 0; cnt < this.termList.length; cnt++) {
      outputStream.writeString(this.termList[cnt]);
      Vector termVector;
  
      float[] tmp = new float[flagConfig.dimension()];
      for (int i = 0; i < flagConfig.dimension(); i++)
        tmp[i] = (float) vT.value[i][cnt];
      termVector = new RealVector(tmp);
      termVector.normalize();
  
      termVector.writeToLuceneStream(outputStream);
    }
    outputStream.close();
    VerbatimLogger.info(
        "Wrote " + cnt + " term vectors incrementally to file " + flagConfig.termvectorsfile() + ".\n");
  
    // Write document vectors.
    // Open file and write headers.
    outputStream = fsDirectory.createOutput(
        VectorStoreUtils.getStoreFileName(flagConfig.docvectorsfile(), flagConfig), IOContext.DEFAULT);
  
    // Write header giving number of dimensions for all vectors and make sure type is real.
    outputStream.writeString(VectorStoreWriter.generateHeaderString(flagConfig));
  
    // Write out document vectors
    for (cnt = 0; cnt < luceneUtils.getNumDocs(); cnt++) {
      String thePath = this.luceneUtils.getDoc(cnt).get(flagConfig.docidfield());
      outputStream.writeString(thePath);
      float[] tmp = new float[flagConfig.dimension()];
  
      for (int i = 0; i < flagConfig.dimension(); i++)
        tmp[i] = (float) uT.value[i][cnt];
      RealVector docVector = new RealVector(tmp);
      docVector.normalize();
      
      docVector.writeToLuceneStream(outputStream);
    }
    outputStream.close();
    VerbatimLogger.info("Wrote " + cnt + " document vectors incrementally to file "
                        + flagConfig.docvectorsfile() + ". Done.\n");
  }

  public static void main(String[] args) throws IllegalArgumentException, IOException {
    FlagConfig flagConfig;
    try {
      flagConfig = FlagConfig.getFlagConfig(args);
      args = flagConfig.remainingArgs;
    } catch (IllegalArgumentException e) {
      System.out.println(usageMessage);
      throw e;
    }
    if (flagConfig.vectortype() != VectorType.REAL) {
      logger.warning("LSA is only supported for real vectors ... setting vectortype to 'real'."); 
    }
    
    if (flagConfig.luceneindexpath().isEmpty()) {
      throw (new IllegalArgumentException("-luceneindexpath must be set."));
    }
    
    if (flagConfig.contentsfields().length != 1) {
      throw new IllegalArgumentException(
          "LSA only supports one -contentsfield, more than this may cause a corrupt matrix.");
    }
    
    LSA lsaIndexer = new LSA(flagConfig.luceneindexpath(), flagConfig);
    SMat A = lsaIndexer.smatFromIndex();
    Svdlib svd = new Svdlib();

    VerbatimLogger.info("Starting SVD using algorithm LAS2 ...\n");

    SVDRec svdR = svd.svdLAS2A(A, flagConfig.dimension());
    DMat vT = svdR.Vt;
    DMat uT = svdR.Ut;
    lsaIndexer.writeOutput(vT, uT);
  }
}

