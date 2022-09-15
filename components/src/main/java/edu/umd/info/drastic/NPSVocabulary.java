package edu.umd.info.drastic;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.apache.commons.rdf.api.IRI;
import org.apache.commons.rdf.api.RDF;
import org.trellisldp.api.RDFFactory;

public class NPSVocabulary {

	private static final RDF rdf = RDFFactory.getInstance();
	
	public static final IRI PCDM_NS = rdf.createIRI("http://pcdm.org/models#");
	public static enum PCDM {
		Collection, Object, hasMember, hasFile;
		
		public IRI iri;
		public String str;
		
		PCDM() {
			this.iri = rdf.createIRI(PCDM_NS.getIRIString()+this.name());
			this.str = this.iri.getIRIString();
		}
	}
	
	public static final IRI ORE_NS = rdf.createIRI("http://www.openarchives.org/ore/terms/");
	public static enum ORE {
		Proxy, proxyIn, proxyFor;
		
		public IRI iri;
		public String str;
		
		ORE() {
			this.iri = rdf.createIRI(ORE_NS.getIRIString()+this.name());
			this.str = this.iri.getIRIString();
		}
	}
	
	public static final IRI IANA_NS = rdf.createIRI("http://www.iana.org/assignments/relation/");
	public static enum IANA {
		next, prev, first, last;
		
		public IRI iri;
		public String str;
		
		IANA() {
			this.iri = rdf.createIRI(IANA_NS.getIRIString()+this.name());
			this.str = this.iri.getIRIString();
		}
	}
	
	//public static final IRI RDF_type = rdf.createIRI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type");
	
	public static final IRI NPS_NS = rdf.createIRI("https://example.nps.gov/2021/nps-workflow#");
	public static enum NPS {
		containsGraph, MissingPageFile, path, hasAccess, hasThumbnail;
		
		public IRI iri;
		public String str;
		
		NPS() {
			this.iri = rdf.createIRI(NPS_NS.getIRIString()+this.name());
			this.str = this.iri.getIRIString();
		}
	}
	
	public static final IRI LDP = rdf.createIRI("http://www.w3.org/ns/ldp#");
	public static final IRI LDP_NonRDFSource = rdf.createIRI(LDP.getIRIString()+"NonRDFSource");
	public static final IRI DCTERMS_NS = rdf.createIRI("http://purl.org/dc/terms/");
	public static enum DCTERMS {
		identifier,
		title,
		creator,
		subject,
		description,
		date,
		rights,
		type,
		language,
		ProvenanceStatement,
		format;

		DCTERMS() {
			this.iri = rdf.createIRI(DCTERMS_NS.getIRIString()+this.name());
			this.str = this.iri.getIRIString();
		}

		public IRI iri;
		public String str;
	}
	public static final IRI ICMS_NS = rdf.createIRI("https://rediscoverysoftware.com/schema/icms_ns/");
	public static enum ICMS {
		RediscoveryExport,
		level,
		Collection_x0020_Title,
		Collection_x0020_Nbr,
		Creator_x003A_Artist,
		History_x003A_Brief_x0020_Note,
		Incl_x0020_Dates("Incl._x0020_Dates"),
		Scope_x003A_Summary_x0020_Note,
		Provenance,
		Scope_x003A_Expansion_x0020_Note,
		Series_x0020_Title,
		Language_x003A_Language_x0020_Code,
		Language_x003A_Language_x0020_Note,
		History_x003A_Expansion_x0020_Note,
		Bulk_x0020_Dates,
		File_x0020_Unit_x0020_Nbr,
		Phys_x0020_Desc,
		Series_x0020_Nbr,
		Sp_x0020_Matl,
		Summ_x0020_Note,
		Title,
		Notes,
		Location,
		Addl_x0020_Acc_x0023_,
		Index_x0020_Terms_x003A_Form,
		id;
		
		public IRI iri;
		public String str;
		
		ICMS() {
			this.iri = rdf.createIRI(ICMS_NS.getIRIString()+this.name());	
			this.str = this.iri.getIRIString();	
		}
		
		ICMS(String myiri) {
			this.iri = rdf.createIRI(ICMS_NS.getIRIString()+myiri);
			this.str = this.iri.getIRIString();	
		}
	}
	
	public static enum ICMS_LEVEL {
		Collection, Subgroup, Series, Subseries, Subsubseries, Location, Box, Folder, Item;
	}
	
	private static Set<ICMS> foo = new HashSet<ICMS>();
    static {
    	foo.add(ICMS.Notes);
    	foo.add(ICMS.Location);
    	foo.add(ICMS.Addl_x0020_Acc_x0023_);
    }
    public static final Set<ICMS> ICMS_FULLTEXT_EXCLUSIONS = Collections.unmodifiableSet(foo);
	
	public static final IRI TIKA_NS = rdf.createIRI("https://example.nps.gov/2021/tika/meta#");
	public static enum TIKA {
		NER_LOCATION, NER_PERSON, NER_ORGANIZATION;
		
		public IRI iri;
		public String str;
		
		TIKA() {
			this.iri = rdf.createIRI(TIKA_NS.getIRIString()+this.name());
			this.str = this.iri.getIRIString();
		}
	}
}
