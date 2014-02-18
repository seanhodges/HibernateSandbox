package database;

import java.util.Map;

public class Document {

	private Integer id;
	private String name;
	private Map<PkgItem, Document> documentbundles;

	public Integer getId() {
		return id;
	}

	public void setId(Integer id) {
		this.id = id;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public Map<PkgItem, Document> getDocumentbundles() {
		return documentbundles;
	}

	public void setDocumentbundles(Map<PkgItem, Document> documentbundles) {
		this.documentbundles = documentbundles;
	}
}
