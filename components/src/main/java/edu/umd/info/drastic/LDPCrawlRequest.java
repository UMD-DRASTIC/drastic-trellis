package edu.umd.info.drastic;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAnySetter;

class LDPCrawlRequest {
	public int depth;
	public String kafkaTopic;
	public String startUri;
	public Map<String, String> options;
	
	public LDPCrawlRequest() {}
	
	public LDPCrawlRequest(String startUri, int depth, String kafkaTopic, Map<String, String> options) {
		this.startUri = startUri;
		this.depth = depth;
		this.kafkaTopic = kafkaTopic;
		this.options = options;
		
	}
	
    @JsonAnySetter
    public void addOptions(String k, String v) {
        options.put(k, v);
    }
    @Override
    public String toString() {
        return "LDPCrawlRequest {" +
                "startUri='" + startUri + '\'' +
                ", depth=" + depth +
                ", kafkaTopic=" + kafkaTopic +
                ", optionsJSON=" + options +
                '}';
    }
    
}
