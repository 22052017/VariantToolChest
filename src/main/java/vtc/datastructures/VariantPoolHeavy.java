package vtc.datastructures;

import htsjdk.samtools.SAMSequenceDictionary;
import htsjdk.samtools.reference.IndexedFastaSequenceFile;
import htsjdk.variant.variantcontext.Allele;
import htsjdk.variant.variantcontext.Genotype;
import htsjdk.variant.variantcontext.GenotypeBuilder;
import htsjdk.variant.variantcontext.GenotypesContext;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.variantcontext.VariantContextBuilder;
import htsjdk.variant.variantcontext.writer.Options;
import htsjdk.variant.variantcontext.writer.VariantContextWriter;
import htsjdk.variant.variantcontext.writer.VariantContextWriterFactory;
import htsjdk.variant.vcf.VCFFormatHeaderLine;
import htsjdk.variant.vcf.VCFHeader;
import htsjdk.variant.vcf.VCFHeaderLine;
import htsjdk.variant.vcf.VCFHeaderLineCount;
import htsjdk.variant.vcf.VCFHeaderLineType;
import htsjdk.variant.vcf.VCFHeaderVersion;
import htsjdk.variant.vcf.VCFInfoHeaderLine;
import htsjdk.variant.vcf.VCFUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.log4j.Logger;

import vtc.tools.utilitybelt.UtilityBelt;
import vtc.tools.varstats.AltType;


/**
 * @author markebbert
 *
 *	VariantPool is designed to handle a pool of variants of class VariantContext and any
 *	necessary operations on those pools. Pools may be thought of as variants from files
 *	such as VCFs or resultant pools after performing set operations between other
 *	VariantPool objects.
 */
/**
 * @author markebbert
 *
 */
public class VariantPoolHeavy extends AbstractVariantPool{
	
//	private static ArrayList<String> usedPoolIDs = new ArrayList<String>();
//	private static ArrayList<String> generatedPoolIDs = new ArrayList<String>();
	
	private static Logger logger = Logger.getLogger(VariantPoolHeavy.class);

	private HashMap<String, VariantContext> hMap;
	private HashMap<String, HashMap<String, VariantContext>> hMapChrPos;
//	private TreeMap<String, VariantContext> tMap;
	private TreeSet<String> contigs;
	
	private String currVarKey;
	private Iterator<String> varKeyIter;

	private Boolean hasGenotypeData;
	
	private int potentialMatchingIndelAlleles;
	private int potentialMatchingIndelRecords;
	
	/* This boolean will track whether the VCF has been fully read into the
	 * VariantPoolHeavy object. Once it has been, then getNextVar should use
	 * the set of variants read into this object.
	 */
	private boolean vcfFileFullyParsed = false;

	
	/****************************************************
	 * Constructors
	 */
	
	/**
	 * Create an empty VariantPool. Use this for building a
	 * VariantPool from scratch rather than reading from a file.
	 * @throws IOException 
	 */
	public VariantPoolHeavy(boolean addChr, String poolID) throws IOException{
		super(addChr, poolID);
		this.init();
	}

	public VariantPoolHeavy(VariantPoolHeavy vp) throws IOException{
		this(vp.getFile(), vp.getPoolID(), vp.addChr());
	}
	
	public VariantPoolHeavy(String filePath, String poolID, boolean addChr) throws IOException{
		this(new File(filePath), poolID, addChr);
	}
	
	public VariantPoolHeavy(File file, String poolID, boolean addChr) throws IOException{
		this(file, poolID, false, addChr);
	}

	public VariantPoolHeavy(File file, String poolID, boolean requireIndex, boolean addChr) throws IOException{
		super(file, poolID, requireIndex, addChr);
		this.init();
		this.parseVCF(this.getFile().getPath(), this.requireIndex());
	}
	
	public VariantPoolHeavy(String inputString, boolean requireIndex, boolean addChr) throws InvalidInputFileException, IOException{
		super(inputString, requireIndex, addChr);
		this.init();
		this.parseVCF(this.getFile().getPath(), this.requireIndex());
	}

//	private void init(File file, String poolID, boolean requireIndex, boolean addChr) throws IOException{
//		init(addChr, poolID);
//
//		this.setFile(file);
//		this.parseVCF(file.getPath(), requireIndex);
//	}
	
	private void init() throws IOException{
		this.hMap = new HashMap<String, VariantContext>();
		this.hMapChrPos = new HashMap<String, HashMap<String,VariantContext>>();
		this.contigs = new TreeSet<String>();
	}
	
	
	
	
	/****************************************************
	 *  Getters
	 */


	
	
	/**
	 * Get variant by chromosome and position
	 * @param chr
	 * @param pos
	 * @return Either a VariantContext object or null
	 */
	public VariantContext getVariant(String chr, int pos, String ref){
		return getVariant(chr + ":" + Integer.toString(pos) + ":" + ref);
	}
	
	private HashMap<String, VariantContext> getVariantsByChrPos(String chr, int pos){
		return hMapChrPos.get(chr + ":" + Integer.toString(pos));
	}

	/**
	 * Get variant by key ('chr:pos:ref')
	 * @param key
	 * @return Either a VariantContext object or null
	 */
	public VariantContext getVariant(String key){
		return this.hMap.get(key);
	}

	/**
	 * Return the number of variants in this pool
	 * @return
	 */
	public int getNumVarRecords(){
		return hMap.size();
	}
	
	public TreeSet<String> getContigs(){
		return this.contigs;
	}
	
	/**
	 * Return an Iterator<String> object to an ordered key set for 
	 * the variants. The keys are formatted as 'chr:pos' and ordered 'naturally'.
	 * @return
	 */
	private Iterator<String> getVariantIterator(){
//		return this.tMap.keySet().iterator();
		ArrayList<String> keys = new ArrayList<String>(this.hMap.keySet());
		Collections.sort(keys, new NaturalOrderComparator());
		return keys.iterator();
	}	
	
	public VariantContext getNextVar() throws IOException{
        VariantContext currVar;
        
        /* VariantPoolHeavy objects read the entire VCF into memory.
         * vcfFileFullyParsed tracks whether we've read the entire VCF
         * into memory. If not, just call AbstractVariantPool's method
         * to getNextVar. If we have read the entire file, start iterating
         * over the stored variants.
         */
		if(!vcfFileFullyParsed){
			currVar = super.getNextVar();
			if(currVar == null){
				vcfFileFullyParsed = true;
				varKeyIter = this.getVariantIterator();
			}
			else{
				return currVar;
			}
		}

		else if(vcfFileFullyParsed){
			if(varKeyIter.hasNext()){
				currVarKey = varKeyIter.next();
				currVar = this.getVariant(currVarKey);
				return currVar;
			}
		}
		/* Reset the iterator before returning null. This is important
		 * when the same VariantPool is used more than once.
		 */
		varKeyIter = this.getVariantIterator();
		return null;
	}

	
	public boolean hasGenotypeData(){

		if(this.hasGenotypeData == null){
			Iterator<String> it = this.getVariantIterator();

			if(!it.hasNext()){
				/* The set is empty. Return false. */
				return false;
			}

			VariantContext vc = this.getVariant(it.next());
			if(vc.hasGenotypes()){
				this.hasGenotypeData = true;
			}
			else{
				this.hasGenotypeData = false;
			}
		}
		return this.hasGenotypeData;
	}
	
	/**
	 * Set the number of potential matching indel alleles
	 * between the VariantPools being compared when
	 * this VariantPool was created. This is only used when
	 * this VariantPool is the result of a Set Operation.
	 * For example, if the number of potential matches in
	 * this VariantPool is 100, and this VariantPool is the
	 * result of an intersect, that means there were 100 
	 * potentially matching INDELs across the VariantPools
	 * compared.
	 */
	public int getPotentialMatchingIndelAlleles(){
		return this.potentialMatchingIndelAlleles;
	}
	
	/**
	 * Set the number of potential matching indel records
	 * between the VariantPools being compared when
	 * this VariantPool was created. This is only used when
	 * this VariantPool is the result of a Set Operation.
	 * For example, if the number of potential matches in
	 * this VariantPool is 100, and this VariantPool is the
	 * result of an intersect, that means there were 100 
	 * potentially matching INDELs across the VariantPools
	 * compared.
	 */
	public int getPotentialMatchingIndelRecords(){
		return this.potentialMatchingIndelRecords;
	}
	
	
	
	
	/****************************************************
	 * Setters
	 */
	
//	public void setFile(File file){
//		this.file = file;
//	}
//	
//	public void setPoolID(String poolID){
//		this.poolID = poolID;
//	}
//	
//	public void setHeader(VCFHeader header){
//		this.header = header;
//	}
//	
//	private void setSamples(SamplePool samples){
//		this.samples = samples;
//	}
//	
//	public void addSamples(SamplePool samples){
//		initSamples();
//		this.samples.addSamples(samples.getSamples());
//	}
//	
//	public void addSamples(TreeSet<String> samples){
//		initSamples();
//		this.samples.addSamples(samples);
//	}
//	
//	private void initSamples(){
//		if(this.samples == null){
//			this.samples = new SamplePool();
//			this.samples.setPoolID(this.poolID); // VariantPools and SamplePools must have same ID
//		}		
//	}
	
	/**
	 * Set the number of potential matching indel alleles
	 * between the VariantPools being compared when
	 * this VariantPool was created. This is only used when
	 * this VariantPool is the result of a Set Operation.
	 * For example, if the number of potential matches in
	 * this VariantPool is 100, and this VariantPool is the
	 * result of an intersect, that means there were 100 
	 * potentially matching INDELs across the VariantPools
	 * compared.
	 * 
	 * @param count
	 */
	public void setPotentialMatchingIndelAlleles(int count){
		this.potentialMatchingIndelAlleles = count;
	}
	
	/**
	 * Set the number of potential matching indel records
	 * between the VariantPools being compared when
	 * this VariantPool was created. This is only used when
	 * this VariantPool is the result of a Set Operation.
	 * For example, if the number of potential matches in
	 * this VariantPool is 100, and this VariantPool is the
	 * result of an intersect, that means there were 100 
	 * potentially matching INDELs across the VariantPools
	 * compared.
	 * 
	 * @param count
	 */
	public void setPotentialMatchingIndelRecords(int count){
		this.potentialMatchingIndelRecords = count;
	}
	
	
	
	
	/****************************************************
	 * Useful operations
	 */
	
	/**
	 * Add a contig value. This will keep track of all contigs
	 * in this VariantPool.
	 * @param contig
	 */
	private void addContig(String contig){
		this.contigs.add(contig);
	}

	
	/**
	 * Read a vcf and add VariantContext objects to the pool. 
	 * 
	 * @param filename
	 * @param requireIndex
	 * @return
	 * @throws IOException 
	 */
	private void parseVCF(String filename, boolean requireIndex) throws IOException{
		
		
//		logger.info("Parsing " + filename + " ...");
        NumberFormat nf = NumberFormat.getInstance(Locale.US);
        
        int count = 0;
        VariantContext currVar = getNextVar();
        while(currVar != null){
        	
            if(count > 1 && count % 10000 == 0) System.out.print("Parsed variants: "
            		+ nf.format(count) + "\r");
            
			/* Create a new SamplePool and add it to the VariantPool */
            if(count == 0){
                SamplePool sp = new SamplePool();
                sp.addSamples(new TreeSet<String>(currVar.getSampleNames()));
                sp.setPoolID(this.getPoolID());
                this.setSamples(sp);
            }
            
            this.addVariant(currVar, false);
            count++;
            currVar = getNextVar();
        }
//
//		/** latest VCF specification */
//		final VCFCodec vcfCodec = new VCFCodec();
//
//		/* get A VCF Reader */
//		FeatureReader<VariantContext> reader = AbstractFeatureReader.getFeatureReader(
//				filename, vcfCodec, requireIndex);
//
//		/* read the header */
//		this.setHeader((VCFHeader)reader.getHeader());
//
//		/** loop over each Variation */
//		Iterator<VariantContext> it = null;
//        it = reader.iterator();
//        VariantContext vc;
//        int count = 0;
//        while ( it.hasNext() ) {
//            
//            if(count > 1 && count % 10000 == 0) System.out.print("Parsed variants: "
//            		+ nf.format(count) + "\r");
//
//            /* get next variation and save it */
//            vc = it.next();
//            
//            /* Create a new SamplePool and add it to the VariantPool */
//            if(count == 0){
//                SamplePool sp = new SamplePool();
//                sp.addSamples(new TreeSet<String>(vc.getSampleNames()));
//                sp.setPoolID(this.getPoolID());
//                this.setSamples(sp);
//            }
//            
//            this.addVariant(vc, false);
//            count++;
//        }
//
//        /* we're done */
//        reader.close();

	}
	
	/**
	 * Add a VariantContext object to the pool
	 * @param v
	 * @param union
	 */
	public void addVariant(VariantContext v, boolean union){
		String currChr = v.getChr();
		String newChr = generateChrString(currChr);
		
		if(!currChr.equals(newChr)){
			currChr = newChr;
			v = buildNewVariantWithChr(newChr, v);
		}

		this.addContig(currChr);
		String chrPosRef = currChr + ":" + Integer.toString(v.getStart()) + ":" + v.getReference();
		String chrPos = currChr + ":" + Integer.toString(v.getStart());
		
		/* If a variant already exists with this chr:pos:ref,
		 * ignore subsequent variants and emit warning. 
		 */
		if(hMap.containsKey(chrPosRef) && !union){
			/* TODO: Determine how to handle VCFs with multiple records
			 * at the same location. Sometimes people represent multiple
			 * alts on different lines. e.g.:
			 * 
			 * chr2 111 C A
			 * chr2 111 C T
			 * 
			 * Or when there's an INDEL. e.g.:
			 * 
			 * chr2 110 CC C (Can be represented as chr2 111 C .)
			 * chr2 111 C  A
			 */
//			VariantContext existingVar = hMap.get(chrPosRef);
//			VariantContext newVar = combineVariants(v, existingVar);
//			
//			/* Add the new variant */
//			hMap.put(chrPosRef, newVar);
//			tMap.put(chrPosRef, newVar);
			
			try{
				throw new VariantPoolException("Found separate variant records with the same Chr, pos, and ref. Ignoring " +
					"subsequent variants at: " + v.getChr() + ":" + v.getStart());
			} catch (VariantPoolException e){
				logger.error(e.getMessage());
			}
		}
		else{
			hMap.put(chrPosRef, v);
//			tMap.put(chrPosRef, v);
			if(!hMapChrPos.containsKey(chrPos)){
				HashMap<String, VariantContext> newHMap = new HashMap<String, VariantContext>();
				newHMap.put(chrPosRef, v);
				hMapChrPos.put(chrPos, newHMap);
			}
			else{
				hMapChrPos.get(chrPos).put(chrPosRef, v);
			}
		}
	}
	
	public void updateVariant(String key, VariantContext newVar){
		hMap.put(key, newVar);
//		tMap.put(key, newVar);
	}
	
	/**
	 * Add or remove 'chr' to chromosome if user requests
	 * @param chr
	 * @return
	 */
	private String generateChrString(String chr){
		if(this.addChr()){
			if(!chr.toLowerCase().startsWith("chr")){
				return "chr" + chr;
			}
		}
		else if(chr.toLowerCase().startsWith("chr")){
			return chr.substring(3);
		}
		return chr;
	}
	
	/**
	 * Build a new variant with the updated 'chr'
	 * 
	 * @param chr
	 * @param var
	 * @return
	 */
	private VariantContext buildNewVariantWithChr(String chr, VariantContext var){
		
		VariantContextBuilder vcBuilder = new VariantContextBuilder();
		vcBuilder.alleles(var.getAlleles());
		vcBuilder.attributes(var.getAttributes());
		vcBuilder.chr(chr);
		vcBuilder.filters(var.getFilters());
		vcBuilder.genotypes(var.getGenotypes());
		vcBuilder.id(var.getID());
		vcBuilder.log10PError(var.getLog10PError());
		vcBuilder.source(var.getSource());
		vcBuilder.start(var.getStart());
		vcBuilder.stop(var.getEnd());
		return vcBuilder.make();
	}
	
	/**
	 * Combine variants with the same chr, pos, and ref found in the same VariantPool
	 * 
	 * @param var
	 * @param alleles
	 * @param genos
	 * @return
	 */
//	private VariantContext combineVariants(VariantContext var1, VariantContext var2){
//		/* Start building the new VariantContext */
//		VariantContextBuilder vcBuilder = new VariantContextBuilder();
//		vcBuilder.chr(var1.getChr());
//		vcBuilder.start(var1.getStart());
//		vcBuilder.stop(var1.getEnd());
//		
//		TreeSet<Allele> allAlleles = new TreeSet<Allele>();
//		allAlleles.addAll(var1.getAlleles());
//		allAlleles.addAll(var2.getAlleles());
//		vcBuilder.alleles(allAlleles);
//		
//		ArrayList<Genotype> genos = new ArrayList<Genotype>();
//		genos.addAll(var1.getGenotypes());
//		genos.addAll(var2.getGenotypes());
//		vcBuilder.genotypes(genos);
//		
//		/* TODO: Figure out how to approach attributes (i.e. INFO). */
////		vcBuilder.attributes(var.getAttributes());
//		return vcBuilder.make();
//	}
	
	/**
	 * Change the sample names for this VariantPool. The newSampleNames
	 * must be in the same order as they are in the file.
	 * 
	 * @param newSampleNames
	 */
	public void changeSampleNames(ArrayList<String> newSampleNames){
		
		/* Update the SamplePool */
        SamplePool newSamples = new SamplePool();
        newSamples.setPoolID(this.getPoolID()); // VariantPools and SamplePools must have same ID
        newSamples.addSamples(new TreeSet<String>(newSampleNames));
		this.setSamples(newSamples);
		
		Iterator<String> varIT = this.getVariantIterator();
		VariantContext currVar;
		String varKey;
		GenotypesContext gcs;
		ArrayList<Genotype> newGenos;
		while(varIT.hasNext()){
			varKey = varIT.next();
			currVar = this.getVariant(varKey);
			gcs = currVar.getGenotypes();
			newGenos = new ArrayList<Genotype>();
			for(int i = 0; i < gcs.size(); i++){
				newGenos.add(renameGenotypeForSample(newSampleNames.get(i), gcs.get(i)));
			}
			currVar = buildVariant(currVar, currVar.getAlleles(), newGenos);
			this.updateVariant(varKey, currVar);
		}
	}
	
	/**
	 * Change the sample name for a genotype
	 * 
	 * @param sampleName
	 * @param geno
	 * @return
	 */
	public static Genotype renameGenotypeForSample(String sampleName, Genotype geno){
		return new GenotypeBuilder(sampleName, geno.getAlleles())
					.AD(geno.getAD())
					.DP(geno.getDP())
					.GQ(geno.getGQ())
					.PL(geno.getPL())
					.attributes(geno.getExtendedAttributes())
					.filters(geno.getFilters())
					.phased(geno.isPhased())
					.make();
	}
	
	public static Genotype changeAllelesForGenotype(Genotype geno, ArrayList<Allele> newAlleles){
		return new GenotypeBuilder(geno.getSampleName(), newAlleles)
					.AD(geno.getAD())
					.DP(geno.getDP())
					.GQ(geno.getGQ())
					.PL(geno.getPL())
					.attributes(geno.getExtendedAttributes())
					.filters(geno.getFilters())
					.phased(geno.isPhased())
					.make();
	}
	
	/**
	 * Build a new variant from an original and add all alleles and genotypes
	 * 
	 * @param var
	 * @param alleles
	 * @param genos
	 * @return
	 */
	public static VariantContext buildVariant(VariantContext var, List<Allele> alleles, ArrayList<Genotype> genos){
		/* Start building the new VariantContext */
		VariantContextBuilder vcBuilder = new VariantContextBuilder();
		vcBuilder.chr(var.getChr());
		vcBuilder.id(var.getID());
		vcBuilder.start(var.getStart());
		vcBuilder.stop(var.getEnd());
		vcBuilder.alleles(alleles);
		vcBuilder.genotypes(genos);
		vcBuilder.filters(var.getFilters());
		vcBuilder.log10PError(var.getLog10PError());
		vcBuilder.source(var.getSource());
		vcBuilder.attributes(var.getAttributes());
		return vcBuilder.make();
	}


	
	/**
	 * Generate a basic header for the VCF
	 * 
	 * @param refDict
	 */
	public void generateBasicHeader(SAMSequenceDictionary refDict, Set<String> sampleNames){
		LinkedHashSet<VCFHeaderLine> headerLines = new LinkedHashSet<VCFHeaderLine>();
		
		/* Add the 'fileFormat' header line (must be first) */
		headerLines.add(new VCFHeaderLine(VCFHeaderVersion.VCF4_1.getFormatString(),
				VCFHeaderVersion.VCF4_1.getVersionString()));
		
		/* Format field must have at least one value. The Genotype in this case. */
		headerLines.add(new VCFFormatHeaderLine("GT", 1, VCFHeaderLineType.String, "Genotype"));
		
		/* Create contig header lines */
		headerLines.addAll(VCFUtils.makeContigHeaderLines(refDict, null));
		
		this.setHeader(new VCFHeader(headerLines, sampleNames));
	}
	
	
	/**
	 * Print a VariantPool to file in the format specified by SupportedFileType. If fileType is
	 * VCF, we must have a SAMSequenceDictionary. If 'repairHeader' is true, create and add missing
	 * header lines.
	 * 
	 * @param filePath
	 * @param vp
	 * @param refDict
	 * @param fileType
	 * @param repairHeader
	 * @throws URISyntaxException 
	 * @throws FileNotFoundException 
	 */
	public static void printVariantPool(String file, VariantPoolHeavy vp, File refDict,
			SupportedFileType fileType, boolean repairHeader) throws URISyntaxException, FileNotFoundException{
		printVariantPool(file, null, vp, refDict, fileType, repairHeader);
	}
	
	/**
	 * Print a VariantPool to file in the format specified by SupportedFileType. If fileType is
	 * VCF, we must have a SAMSequenceDictionary. If 'repairHeader' is true, create and add missing
	 * header lines.
	 * 
	 * @param fileName
	 * @param outputDirectory
	 * @param vp
	 * @param refDict
	 * @param fileType
	 * @param repairHeader
	 * @throws URISyntaxException
	 * @throws FileNotFoundException
	 */
	public static void printVariantPool(String fileName, String outputDirectory,
			VariantPoolHeavy vp, File refDict, SupportedFileType fileType, boolean repairHeader) throws URISyntaxException, FileNotFoundException{
		
		File file;
		String normalizedPath;
		
		/* If a specific output directory is specified, normalize the path and prepend it to the fileName */
		if(outputDirectory != null){
			normalizedPath = new URI(outputDirectory).normalize().getPath();
			file = new File(normalizedPath + fileName);
		}
		else{
			file = new File(fileName);
		}

		if(fileType == SupportedFileType.VCF){
			printVariantPoolToVCF(file, vp, refDict, repairHeader);
		}
	}
	
	/**
	 * Print a VariantPool to a file in VCF format. Must have a SAMSequenceDictionary for a reference file. If
	 * 'repairHeader' is true, create and add missing header lines.
	 * 
	 * @param file
	 * @param vp
	 * @param refDict
	 * @param repairHeader
	 * @throws FileNotFoundException
	 */
	private static void printVariantPoolToVCF(File file, VariantPoolHeavy vp, File refDict, boolean repairHeader) throws FileNotFoundException{
		
		if(refDict == null){
			throw new RuntimeException("Received a 'null' SAMSequenceDictionary. Something is very wrong!");
		}

		EnumSet<Options> es;
		if(repairHeader){
			es = EnumSet.of(Options.INDEX_ON_THE_FLY);
		}
		else{
			es = EnumSet.of(Options.INDEX_ON_THE_FLY, Options.ALLOW_MISSING_FIELDS_IN_HEADER);
		}
		SAMSequenceDictionary dict = new IndexedFastaSequenceFile(refDict).getSequenceDictionary();
		if(dict == null){
			throw new FileNotFoundException("The reference sequence specified ("
					+ refDict.getAbsolutePath() +
					") does not have the appropriate dictionary file. Please use"
					+ " Picard's CreateSequenceDictionary.jar to generate this file.");
		}
		VariantContextWriter writer = VariantContextWriterFactory.create(file, dict, es);
		
		if(vp.getHeader() == null){
			vp.generateBasicHeader(dict, vp.getSamples());
		}
		
		writer.writeHeader(vp.getHeader());
		
		boolean rewrite = false;
		Iterator<String> it = vp.getVariantIterator();
		VariantContext vc;
		while(it.hasNext()){
			vc = vp.getVariant(it.next());
			
			/* Write variant to file. 'writer' will throw an IllegalStateException
			 * if a variant has annotations that are not in the header. If this
			 * happens, determine what's missing and add a dummy header
			 * line (assuming the user chooses this option).
			 */
			try{
				writer.add(vc);
			} catch (IllegalStateException e){
				
				/* An example of the expected message is:
				 * "Key AC found in VariantContext field INFO at 20:13566260
				 * but this key isn't defined in the VCFHeader.  We require
				 * all VCFs to have complete VCF headers by default."
				 * 
				 * check if the exception's message appears to match and add
				 * missing line if it does.
				 */
				if(repairHeader && e.getMessage().contains("found in VariantContext field")){
					addMissingHeaderLineAndWriteVariant(e, vp, writer, vc);
					
					/* There should now be dummy header lines added for all
					 * missing values, but since
					 * we've already started writing to the file, we need to 
					 * stop, close and rewrite.
					 */
					rewrite = true;
					break;
				}
				
				// else throw the error because something is wrong
				throw e;
			}
		}
		
		writer.close();
		if(rewrite){
			printVariantPoolToVCF(file, vp, refDict, repairHeader);
		}
	}
	
	/**
	 * Recursively add missing header line(s) to VariantPool and attempt to write the variant
	 * to file. 
	 * 
	 * TODO: It would be WAY better to check whether the header has all necessary lines BEFORE
	 * trying to write anything. This is approach is just clunky. We should also get a list of
	 * any standardized annotations with descriptions and use those were possible rather than
	 * giving a dummy description.
	 * 
	 * @param e
	 * @param vp
	 */
	private static void addMissingHeaderLineAndWriteVariant(Exception e, VariantPoolHeavy vp,
			VariantContextWriter writer, VariantContext vc){
		
		/* Extract the missing 'Key' and 'field */
		Pattern missingKeyPattern = Pattern.compile("^Key (\\w+) found in VariantContext field (\\w+)");
		Matcher m = missingKeyPattern.matcher(e.getMessage());
		m.find();
		
		/* Something's wrong if there are no matches */
		if(m.groupCount() == 0){
			throw new RuntimeException("Could not determine the missing annotation. Something is very wrong!" +
					"Original message:" + e.getMessage());
		}
		String missingKey = m.group(1);
		String lineType = m.group(2);
		String description = "This is a dummy description";
		
		String message = "Variant pool (" + vp.getPoolID() + ") missing header line with key '" +
				missingKey + "' and type '" + lineType + ". Creating and adding dummy line to header.";

		logger.warn(message);
		System.out.println("Warning: " + message);
		
		/* Create missing line based on the type */
		if("INFO".equals(lineType)){
			vp.getHeader().addMetaDataLine(new VCFInfoHeaderLine(missingKey, VCFHeaderLineCount.UNBOUNDED, VCFHeaderLineType.String, description));
		}
		else if("FORMAT".equals(lineType)){
			vp.getHeader().addMetaDataLine(new VCFFormatHeaderLine(missingKey, 1, VCFHeaderLineType.String, description));
		}
		else{
			throw new RuntimeException("Could not determine missing header line type." +
					"Something is very wrong! Original message: " + e.getMessage());
		}
		
		/* Attempt to write to file again */
		try{
			writer.add(vc);
		} catch (IllegalStateException ex){
			if(ex.getMessage().contains("found in VariantContext field")){
				addMissingHeaderLineAndWriteVariant(ex, vp, writer, vc);
			}
		}
	}
	
	/**
	 * Count how many of the alternate alleles in var overlap with any alternate
	 * in this VariantPool within +/- indelLength.
	 * The INDELs must be of the same type (i.e., both are insertions or both are
	 * deletions, etc.) and length.
	 * @param var
	 * @return
	 */
	public int getOverlappingIndelAlleleCount(VariantContext var){
		int overlappingIndelAlleleCount = 0;
		Allele ref = var.getReference();
		List<Allele> alts = var.getAlternateAlleles();
		int indelLength;
		for(Allele alt : alts){
			AltType type = UtilityBelt.determineAltType(ref, alt);
			/* Make sure this alt is an indel. If the variant is mixed,
			 * we'll wind up looking at SNVs too.
			 */
			if(!UtilityBelt.altTypeIsIndel(type)){ continue; }
			indelLength = ref.length() > alt.length() ? ref.length() : alt.length(); // length is the longer of the two
			if(getOverlappingIndel(var.getChr(), var.getStart(), indelLength, type) != null){
//				System.out.println("var2: " + var.getChr() + ":" + var.getStart() + ":" + ref + ":" + alt + "\tLength: " + indelLength);
				overlappingIndelAlleleCount++;
			}
		}
		return overlappingIndelAlleleCount;
	}
	
	/**
	 * Test if any variants in this pool overlap within +/- indelLength.
	 * The INDELs must be of the same type (i.e., both are insertions or both are
	 * deletions, etc.) and length.
	 * @param chr
	 * @param pos
	 * @param indelLength
	 * @param type
	 * @return
	 */
	public VariantContext getOverlappingIndel(String chr, int pos, int indelLength, AltType type){
		
		int currIndelLength;
		/* For each variant within +/- indelLength, check if there's another
		 * indel with the same length. The indels must be of the same type.
		 * i.e., they must both be insertions or both be deletions, etc.
		 */
		for(int i = pos - indelLength; i <= pos + indelLength; i++){

			/* Get all variants at a given chr and pos */
			HashMap<String,VariantContext> vars = this.getVariantsByChrPos(chr, i);
			
			/* Continue if null */
			if(vars == null){
				continue;
			}

			Iterator<String> it = vars.keySet().iterator();
			VariantContext var;
			String key;
			Allele ref;
			List<Allele> alts;
			
			/* For each variant record at chr:pos */
			while(it.hasNext()){
				key = it.next();
				var = vars.get(key);
				
				ref = var.getReference();
				alts = var.getAlternateAlleles();
				
				/* If any alternate is of the same type and length,
				 * return true
				 */
				for(Allele alt : alts){
					currIndelLength = ref.length() > alt.length() ? ref.length() : alt.length(); // length is the longer of the two
					if(UtilityBelt.determineAltType(ref, alt) == type
							&& currIndelLength == indelLength){
//						System.out.println("\nvar1: " + var.getChr() + ":" + var.getStart() + ":" + ref + ":" + alt);
						return var;
					}
				}
			}
		}
		return null;
	}
	
}
