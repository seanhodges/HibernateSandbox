import database.Document;
import database.PkgItem;
import org.hibernate.Hibernate;
import org.hibernate.collection.PersistentMap;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.orm.hibernate3.HibernateTemplate;
import org.springframework.orm.hibernate3.HibernateTransactionManager;
import org.springframework.orm.hibernate3.LocalSessionFactoryBean;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class HibernateMapTest {

	private static final String TEST_DIALECT = "org.hibernate.dialect.HSQLDialect";
	private static final String TEST_DRIVER = "org.hsqldb.jdbcDriver";
	private static final String TEST_URL = "jdbc:hsqldb:mem:adportal";
	private static final String TEST_USER = "sa";
	private static final String TEST_PASSWORD = "";

	private HibernateTemplate hibernateTemplate;
	private TransactionTemplate transactionTemplate;

	@Before
	public void setUp() throws Exception {
		hibernateTemplate = new HibernateTemplate();
		LocalSessionFactoryBean sessionFactory = new LocalSessionFactoryBean();
		sessionFactory.getHibernateProperties().put("hibernate.dialect", TEST_DIALECT);
		sessionFactory.getHibernateProperties().put("hibernate.connection.driver_class", TEST_DRIVER);
		sessionFactory.getHibernateProperties().put("hibernate.connection.password", TEST_PASSWORD);
		sessionFactory.getHibernateProperties().put("hibernate.connection.url", TEST_URL);
		sessionFactory.getHibernateProperties().put("hibernate.connection.username", TEST_USER);
		sessionFactory.getHibernateProperties().put("hibernate.hbm2ddl.auto", "create");
		sessionFactory.getHibernateProperties().put("hibernate.show_sql", "true");
		sessionFactory.getHibernateProperties().put("hibernate.jdbc.batch_size", "0");
		sessionFactory.getHibernateProperties().put("hibernate.cache.use_second_level_cache", "false");

		sessionFactory.setMappingDirectoryLocations(new Resource[]{new ClassPathResource("database")});
		sessionFactory.afterPropertiesSet();

		hibernateTemplate.setSessionFactory(sessionFactory.getObject());

		transactionTemplate = new TransactionTemplate(new HibernateTransactionManager(sessionFactory.getObject()));
	}

	@After
	public void tearDown() throws Exception {
		hibernateTemplate.getSessionFactory().close();
	}

	/**
	 * This is the original map inspecting solution suggested by jhadesdev
	 */
	@Test
	public void testFetchEntityWithMapWithEntireTestInTransaction() throws Exception {

		transactionTemplate.execute(new TransactionCallbackWithoutResult() {
			protected void doInTransactionWithoutResult(TransactionStatus status) {
				// Store the entities and mapping
				PkgItem key = new PkgItem();
				key.setName("pkgitem1");
				hibernateTemplate.persist(key);

				Document doc2 = new Document();
				doc2.setName("doc2");
				hibernateTemplate.persist(doc2);

				Document doc1 = new Document();
				doc1.setName("doc1");
				HashMap<PkgItem, Document> documentbundles = new HashMap<PkgItem, Document>();
				documentbundles.put(key, doc2);
				doc1.setDocumentbundles(documentbundles);
				hibernateTemplate.persist(doc1);

				// Now attempt a query
				List results = hibernateTemplate.find("from database.Document d where d.name = 'doc1'");
				Document result = (Document) results.get(0);

				// Check the doc was returned
				assertEquals("doc1", result.getName());

				key = (PkgItem) hibernateTemplate.find("from database.PkgItem").get(0);
				Set<PkgItem> bundleKeys = result.getDocumentbundles().keySet();

				assertEquals(key, bundleKeys.iterator().next());
			}
		});

	}

	/**
	 * I reduced the transaction to wrap just the query and assertion
	 */
	@Test
	public void testFetchEntityWithMapWithPkgItemLookupInTransaction() throws Exception {

		// Store the entities and mapping
		final PkgItem key = new PkgItem();
		key.setName("pkgitem1");
		hibernateTemplate.persist(key);

		Document doc2 = new Document();
		doc2.setName("doc2");
		hibernateTemplate.persist(doc2);

		Document doc1 = new Document();
		doc1.setName("doc1");
		HashMap<PkgItem, Document> documentbundles = new HashMap<PkgItem, Document>();
		documentbundles.put(key, doc2);
		doc1.setDocumentbundles(documentbundles);
		hibernateTemplate.persist(doc1);

		// Wrap this command in a transaction so we have an open session
		transactionTemplate.execute(new TransactionCallbackWithoutResult() {
			@Override
			protected void doInTransactionWithoutResult(TransactionStatus transactionStatus) {

				// Now attempt a query
				List results = hibernateTemplate.find("from database.Document d where d.name = 'doc1'");
				final Document result = (Document) results.get(0);
				assertEquals("doc1", result.getName());

				final PkgItem checkKey = (PkgItem) hibernateTemplate.find("from database.PkgItem").get(0);

				Set<PkgItem> bundleKeys = result.getDocumentbundles().keySet();
				assertEquals(checkKey, bundleKeys.iterator().next());

				assertEquals("doc2", result.getDocumentbundles().get(checkKey).getName());
			}
		});
	}

	/**
	 * I have a requirement that the package item has already been fetched before the
	 * lookup is done. To allow the PersistentMap to work I have to merge the package item
	 * key into the active transaction.
	 *
	 * I've also moved the assertion out of the transaction
	 */
	@Test
	public void testFetchEntityWithMapWithParentDocumentAndPkgItemPassedIntoTransaction() throws Exception {

		// Store the entities and mapping
		PkgItem pkgItem = new PkgItem();
		pkgItem.setName("pkgitem1");
		hibernateTemplate.persist(pkgItem);

		Document doc2 = new Document();
		doc2.setName("doc2");
		hibernateTemplate.persist(doc2);

		Document doc1 = new Document();
		doc1.setName("doc1");
		HashMap<PkgItem, Document> documentbundles = new HashMap<PkgItem, Document>();
		documentbundles.put(pkgItem, doc2);
		doc1.setDocumentbundles(documentbundles);
		hibernateTemplate.persist(doc1);

		Document result = getChildDocumentForPackageItem(doc1, pkgItem);
		assertEquals("doc2", result.getName());
	}

	private Document getChildDocumentForPackageItem(final Document parent, final PkgItem pkgItem) {
		return transactionTemplate.execute(new TransactionCallback<Document>() {
			@Override
			public Document doInTransaction(TransactionStatus transactionStatus) {
				if (parent.getId() == null || pkgItem.getId() == null) return null;

				// Merge the parent document and package item into this transaction
				Document container = hibernateTemplate.merge(parent);
				PkgItem key = hibernateTemplate.merge(pkgItem);

				// Perform the lookup
				return container.getDocumentbundles().get(key);
			}
		});
	}

	/**
	 * I wanted to see if the lookup command could be a smaller unit of work, where I've already looked up
	 * the parent document and package item, and I just want to perform the lookup in the transaction. This
	 * works, but it causes both the document and the package item to be fetched again within the transaction,
	 * which is clearly wasteful.
	 */
	@Test
	public void testFetchEntityWithMapWithParentDocumentAndPkgItemEntitiesLookedUpAndPassedIntoTransaction() throws Exception {

		// Store the entities and mapping
		PkgItem pkgItem = new PkgItem();
		pkgItem.setName("pkgitem1");
		hibernateTemplate.persist(pkgItem);

		Document doc2 = new Document();
		doc2.setName("doc2");
		hibernateTemplate.persist(doc2);

		Document doc1 = new Document();
		doc1.setName("doc1");
		HashMap<PkgItem, Document> documentbundles = new HashMap<PkgItem, Document>();
		documentbundles.put(pkgItem, doc2);
		doc1.setDocumentbundles(documentbundles);
		hibernateTemplate.persist(doc1);

		// We're simulating an earlier fetch of both the document and the package item to pass in
		pkgItem = (PkgItem)hibernateTemplate.find("from database.PkgItem").get(0);
		List results = hibernateTemplate.find("from database.Document d where d.name = 'doc1'");
		doc1 = (Document)results.get(0);

		Document result = getChildDocumentForPackageItem(doc1, pkgItem);
		assertEquals("doc2", result.getName());
	}

	/**
	 * A more optimal solution; pull the whole hashmap within the transaction, replacing
	 * the keys in a second pass, and return as a standard map
	 */
	@Test
	public void testFetchAllChildDocumentsInSingleTransaction() throws Exception {

		// Store the entities and mapping
		PkgItem pkgItem = new PkgItem();
		pkgItem.setName("pkgitem1");
		hibernateTemplate.persist(pkgItem);

		Document doc2 = new Document();
		doc2.setName("doc2");
		hibernateTemplate.persist(doc2);

		Document doc1 = new Document();
		doc1.setName("doc1");
		HashMap<PkgItem, Document> documentbundles = new HashMap<PkgItem, Document>();
		documentbundles.put(pkgItem, doc2);
		doc1.setDocumentbundles(documentbundles);
		hibernateTemplate.persist(doc1);

		Map<PkgItem, Document> result = getChildDocumentsForPackageItems(doc1, Arrays.asList(pkgItem));

		// Now we can perform a standard lookup
		assertEquals("doc2", result.get(pkgItem).getName());
	}

	private Map<PkgItem, Document> getChildDocumentsForPackageItems(final Document parent, final List<PkgItem> pkgItems) {
		return transactionTemplate.execute(new TransactionCallback< Map<PkgItem, Document> >() {
			@Override
			public Map<PkgItem, Document> doInTransaction(TransactionStatus transactionStatus) {
				if (parent.getId() == null) return null;

				// Merge the parent document into this transaction
				Document container = hibernateTemplate.merge(parent);

				// Copy the original package items into the key set
				Map<PkgItem, Document> out = new HashMap<PkgItem, Document>();
				for (PkgItem dbKey : container.getDocumentbundles().keySet()) {
					int keyIndex = pkgItems.indexOf(dbKey);
					if (keyIndex > -1) out.put(pkgItems.get(keyIndex), container.getDocumentbundles().get(dbKey));
				}
				return out;
			}
		});
	}
}
