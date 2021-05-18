package edu.umd.info.drastic;

import org.apache.commons.rdf.api.IRI;
import org.apache.commons.rdf.api.RDF;
import org.trellisldp.api.RDFFactory;

public class NPSVocabulary {

	private static final RDF rdf = RDFFactory.getInstance();
	
	public static final IRI PCDM = rdf.createIRI("http://pcdm.org/models#");
	public static final IRI PCDM_Object = rdf.createIRI(PCDM.getIRIString()+"Object");
	public static final IRI PCDM_hasMember = rdf.createIRI(PCDM.getIRIString()+"hasMember");
	public static final IRI PCDM_hasFile = rdf.createIRI(PCDM.getIRIString()+"hasFile");
	public static final IRI ORE = rdf.createIRI("http://www.openarchives.org/ore/terms/");
	public static final IRI ORE_Proxy = rdf.createIRI(ORE.getIRIString()+"Proxy");
	public static final IRI ORE_proxyIn = rdf.createIRI(ORE.getIRIString()+"proxyIn");
	public static final IRI ORE_proxyFor = rdf.createIRI(ORE.getIRIString()+"proxyFor");
	public static final IRI IANA = rdf.createIRI("http://www.iana.org/assignments/relation/");
	public static final IRI IANA_next = rdf.createIRI(IANA.getIRIString()+"next");
	public static final IRI IANA_prev = rdf.createIRI(IANA.getIRIString()+"prev");
	public static final IRI IANA_first = rdf.createIRI(IANA.getIRIString()+"first");
	public static final IRI IANA_last = rdf.createIRI(IANA.getIRIString()+"last");
	public static final IRI RDF_type = rdf.createIRI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type");
	public static final IRI NPS = rdf.createIRI("https://example.nps.gov/2021/nps-workflow#");
	public static final IRI NPS_containsGraph = rdf.createIRI(NPS.getIRIString()+"containsGraph");
	public static final IRI NPS_MissingPageFile = rdf.createIRI(NPS.getIRIString()+"MissingPageFile");
	public static final IRI LDP = rdf.createIRI("http://www.w3.org/ns/ldp#");
	public static final IRI LDP_NonRDFSource = rdf.createIRI(LDP.getIRIString()+"NonRDFSource");
}
