package org.fao.geonet.kernel.datamanager.base;

import static org.springframework.data.jpa.domain.Specifications.where;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.PostConstruct;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.criteria.Root;

import org.apache.commons.lang.StringUtils;
import org.fao.geonet.ApplicationContextHolder;
import org.fao.geonet.constants.Edit;
import org.fao.geonet.constants.Geonet;
import org.fao.geonet.constants.Params;
import org.fao.geonet.domain.Constants;
import org.fao.geonet.domain.Group;
import org.fao.geonet.domain.ISODate;
import org.fao.geonet.domain.Metadata;
import org.fao.geonet.domain.MetadataCategory;
import org.fao.geonet.domain.MetadataDataInfo;
import org.fao.geonet.domain.MetadataDataInfo_;
import org.fao.geonet.domain.MetadataFileUpload;
import org.fao.geonet.domain.MetadataFileUpload_;
import org.fao.geonet.domain.MetadataSourceInfo;
import org.fao.geonet.domain.MetadataType;
import org.fao.geonet.domain.MetadataValidation;
import org.fao.geonet.domain.Metadata_;
import org.fao.geonet.domain.OperationAllowed;
import org.fao.geonet.domain.OperationAllowedId;
import org.fao.geonet.domain.Pair;
import org.fao.geonet.domain.ReservedGroup;
import org.fao.geonet.domain.ReservedOperation;
import org.fao.geonet.domain.User;
import org.fao.geonet.kernel.AccessManager;
import org.fao.geonet.kernel.EditLib;
import org.fao.geonet.kernel.HarvestInfoProvider;
import org.fao.geonet.kernel.SchemaManager;
import org.fao.geonet.kernel.ThesaurusManager;
import org.fao.geonet.kernel.UpdateDatestamp;
import org.fao.geonet.kernel.XmlSerializer;
import org.fao.geonet.kernel.datamanager.IMetadataIndexer;
import org.fao.geonet.kernel.datamanager.IMetadataManager;
import org.fao.geonet.kernel.datamanager.IMetadataOperations;
import org.fao.geonet.kernel.datamanager.IMetadataSchemaUtils;
import org.fao.geonet.kernel.datamanager.IMetadataUtils;
import org.fao.geonet.kernel.datamanager.IMetadataValidator;
import org.fao.geonet.kernel.schema.MetadataSchema;
import org.fao.geonet.kernel.search.LuceneSearcher;
import org.fao.geonet.kernel.search.MetaSearcher;
import org.fao.geonet.kernel.search.SearchManager;
import org.fao.geonet.kernel.search.SearchParameter;
import org.fao.geonet.kernel.search.SearcherType;
import org.fao.geonet.kernel.search.index.IndexingList;
import org.fao.geonet.kernel.setting.SettingManager;
import org.fao.geonet.kernel.setting.Settings;
import org.fao.geonet.lib.Lib;
import org.fao.geonet.notifier.MetadataNotifierManager;
import org.fao.geonet.repository.GroupRepository;
import org.fao.geonet.repository.MetadataCategoryRepository;
import org.fao.geonet.repository.MetadataFileUploadRepository;
import org.fao.geonet.repository.MetadataRatingByIpRepository;
import org.fao.geonet.repository.MetadataRepository;
import org.fao.geonet.repository.MetadataStatusRepository;
import org.fao.geonet.repository.MetadataValidationRepository;
import org.fao.geonet.repository.OperationAllowedRepository;
import org.fao.geonet.repository.PathSpec;
import org.fao.geonet.repository.SortUtils;
import org.fao.geonet.repository.Updater;
import org.fao.geonet.repository.UserRepository;
import org.fao.geonet.repository.UserSavedSelectionRepository;
import org.fao.geonet.repository.specification.MetadataFileUploadSpecs;
import org.fao.geonet.repository.specification.MetadataSpecs;
import org.fao.geonet.repository.specification.OperationAllowedSpecs;
import org.fao.geonet.utils.Log;
import org.fao.geonet.utils.Xml;
import org.jdom.Element;
import org.jdom.Namespace;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.TransactionStatus;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Maps;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;

import jeeves.constants.Jeeves;
import jeeves.server.ServiceConfig;
import jeeves.server.UserSession;
import jeeves.server.context.ServiceContext;
import jeeves.transaction.TransactionManager;
import jeeves.transaction.TransactionTask;
import jeeves.xlink.Processor;

public class BaseMetadataManager implements IMetadataManager {

    @Autowired
    private IMetadataUtils metadataUtils;
    @Autowired
    private IMetadataIndexer metadataIndexer;
    @Autowired
    private IMetadataValidator metadataValidator;
    @Autowired
    private IMetadataOperations metadataOperations;
    @Autowired
    private IMetadataSchemaUtils metadataSchemaUtils;
    @Autowired
    private GroupRepository groupRepository;
    @Autowired
    private MetadataStatusRepository metadataStatusRepository;
    @Autowired
    private MetadataValidationRepository metadataValidationRepository;
    @Autowired
    private MetadataRepository metadataRepository;
    @Autowired
    private SearchManager searchManager;

    private EditLib editLib;
    @Autowired
    private MetadataRatingByIpRepository metadataRatingByIpRepository;
    @Autowired
    private MetadataFileUploadRepository metadataFileUploadRepository;
    @Autowired(required = false)
    private XmlSerializer xmlSerializer;
    @Autowired
    @Lazy
    private SettingManager settingManager;
    @Autowired
    private MetadataCategoryRepository metadataCategoryRepository;
    @Autowired(required = false)
    private HarvestInfoProvider harvestInfoProvider;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private SchemaManager schemaManager;
    @Autowired
    private ThesaurusManager thesaurusManager;
    @Autowired
    private AccessManager accessManager;
    @Autowired
    private UserSavedSelectionRepository userSavedSelectionRepository;

    private static final int METADATA_BATCH_PAGE_SIZE = 100000;
    private String baseURL;

    @Autowired
    private ApplicationContext _applicationContext;
    @PersistenceContext
    private EntityManager _entityManager;

    @Override
    public EditLib getEditLib() {
        return editLib;
    }

    /**
     * To avoid cyclic references on autowired
     */
    @PostConstruct
    public void init() {
        editLib = new EditLib(schemaManager);
        metadataValidator.setMetadataManager(this);
        metadataUtils.setMetadataManager(this);
    }

    public void init(ServiceContext context, Boolean force) throws Exception {
        metadataUtils = context.getBean(IMetadataUtils.class);
        metadataIndexer = context.getBean(IMetadataIndexer.class);
        metadataStatusRepository = context.getBean(MetadataStatusRepository.class);
        metadataValidationRepository = context.getBean(MetadataValidationRepository.class);
        metadataRepository = context.getBean(MetadataRepository.class);
        metadataValidator = context.getBean(IMetadataValidator.class);
        metadataSchemaUtils = context.getBean(IMetadataSchemaUtils.class);
        searchManager = context.getBean(SearchManager.class);
        metadataRatingByIpRepository = context.getBean(MetadataRatingByIpRepository.class);
        metadataFileUploadRepository = context.getBean(MetadataFileUploadRepository.class);
        groupRepository = context.getBean(GroupRepository.class);
        xmlSerializer = context.getBean(XmlSerializer.class);
        settingManager = context.getBean(SettingManager.class);
        metadataCategoryRepository = context.getBean(MetadataCategoryRepository.class);
        try {
            harvestInfoProvider = context.getBean(HarvestInfoProvider.class);
        } catch (Exception e) {
            // If it doesn't exist, that's fine
        }
        userRepository = context.getBean(UserRepository.class);
        schemaManager = context.getBean(SchemaManager.class);
        thesaurusManager = context.getBean(ThesaurusManager.class);
        accessManager = context.getBean(AccessManager.class);

        // From DataManager:

        // get lastchangedate of all metadata in index
        Map<String, String> docs = getSearchManager().getDocsChangeDate();

        // set up results HashMap for post processing of records to be indexed
        ArrayList<String> toIndex = new ArrayList<String>();

        if (Log.isDebugEnabled(Geonet.DATA_MANAGER))
            Log.debug(Geonet.DATA_MANAGER, "INDEX CONTENT:");

        Sort sortByMetadataChangeDate = SortUtils.createSort(Metadata_.dataInfo, MetadataDataInfo_.changeDate);
        int currentPage = 0;
        Page<Pair<Integer, ISODate>> results = metadataRepository
                .findAllIdsAndChangeDates(new PageRequest(currentPage, METADATA_BATCH_PAGE_SIZE, sortByMetadataChangeDate));

        // index all metadata in DBMS if needed
        while (results.getNumberOfElements() > 0) {
            for (Pair<Integer, ISODate> result : results) {

                // get metadata
                String id = String.valueOf(result.one());

                if (Log.isDebugEnabled(Geonet.DATA_MANAGER)) {
                    Log.debug(Geonet.DATA_MANAGER, "- record (" + id + ")");
                }

                String idxLastChange = docs.get(id);

                // if metadata is not indexed index it
                if (idxLastChange == null) {
                    Log.debug(Geonet.DATA_MANAGER, "-  will be indexed");
                    toIndex.add(id);

                    // else, if indexed version is not the latest index it
                } else {
                    docs.remove(id);

                    String lastChange = result.two().toString();

                    if (Log.isDebugEnabled(Geonet.DATA_MANAGER))
                        Log.debug(Geonet.DATA_MANAGER, "- lastChange: " + lastChange);
                    if (Log.isDebugEnabled(Geonet.DATA_MANAGER))
                        Log.debug(Geonet.DATA_MANAGER, "- idxLastChange: " + idxLastChange);

                    // date in index contains 't', date in DBMS contains 'T'
                    if (force || !idxLastChange.equalsIgnoreCase(lastChange)) {
                        if (Log.isDebugEnabled(Geonet.DATA_MANAGER))
                            Log.debug(Geonet.DATA_MANAGER, "-  will be indexed");
                        toIndex.add(id);
                    }
                }
            }

            currentPage++;
            results = metadataRepository
                    .findAllIdsAndChangeDates(new PageRequest(currentPage, METADATA_BATCH_PAGE_SIZE, sortByMetadataChangeDate));
        }

        // if anything to index then schedule it to be done after servlet is
        // up so that any links to local fragments are resolvable
        if (toIndex.size() > 0) {
            metadataIndexer.batchIndexInThreadPool(context, toIndex);
        }

        if (docs.size() > 0) { // anything left?
            if (Log.isDebugEnabled(Geonet.DATA_MANAGER)) {
                Log.debug(Geonet.DATA_MANAGER, "INDEX HAS RECORDS THAT ARE NOT IN DB:");
            }
        }

        // remove from index metadata not in DBMS
        for (String id : docs.keySet()) {
            getSearchManager().delete(id);

            if (Log.isDebugEnabled(Geonet.DATA_MANAGER)) {
                Log.debug(Geonet.DATA_MANAGER, "- removed record (" + id + ") from index");
            }
        }
    }

    private SearchManager getSearchManager() {
        return searchManager;
    }

    /**
     * You should not use a direct flush. If you need to use this to properly run your code, you are missing something. Check the
     * transaction annotations and try to comply to Spring/Hibernate
     */
    @Override
    @Deprecated
    public void flush() {
        TransactionManager.runInTransaction("DataManager flush()", getApplicationContext(),
                TransactionManager.TransactionRequirement.CREATE_ONLY_WHEN_NEEDED, TransactionManager.CommitBehavior.ALWAYS_COMMIT, false,
                new TransactionTask<Object>() {
                    @Override
                    public Object doInTransaction(TransactionStatus transaction) throws Throwable {
                        _entityManager.flush();
                        return null;
                    }
                });

    }

    private ApplicationContext getApplicationContext() {
        final ConfigurableApplicationContext applicationContext = ApplicationContextHolder.get();
        return applicationContext == null ? _applicationContext : applicationContext;
    }

    private void deleteMetadataFromDB(ServiceContext context, String id) throws Exception {
        Metadata metadata = metadataRepository.findOne(Integer.valueOf(id));
        if (! settingManager.getValueAsBool(Settings.SYSTEM_XLINK_ALLOW_REFERENCED_DELETION) &&
                metadata.getDataInfo().getType() == MetadataType.SUB_TEMPLATE) {
            MetaSearcher searcher = searcherForReferencingMetadata(context, metadata);
            Map<Integer, Metadata> result = ((LuceneSearcher) searcher).getAllMdInfo(context, 1);
            if (result.size() > 0) {
                throw new Exception("this template is referenced.");
            }
        }
        // --- remove operations
        metadataOperations.deleteMetadataOper(context, id, false);

        int intId = Integer.parseInt(id);
        metadataRatingByIpRepository.deleteAllById_MetadataId(intId);
        metadataValidationRepository.deleteAllById_MetadataId(intId);
        metadataStatusRepository.deleteAllById_MetadataId(intId);
        userSavedSelectionRepository.deleteAllByUuid(metadataUtils.getMetadataUuid(id));

        // Logical delete for metadata file uploads
        PathSpec<MetadataFileUpload, String> deletedDatePathSpec = new PathSpec<MetadataFileUpload, String>() {
            @Override
            public javax.persistence.criteria.Path<String> getPath(Root<MetadataFileUpload> root) {
                return root.get(MetadataFileUpload_.deletedDate);
            }
        };

        metadataFileUploadRepository.createBatchUpdateQuery(deletedDatePathSpec, new ISODate().toString(),
                MetadataFileUploadSpecs.isNotDeletedForMetadata(intId));

        // --- remove metadata
        getXmlSerializer().delete(id, context);
    }
    
    private MetaSearcher searcherForReferencingMetadata(ServiceContext context, Metadata metadata) throws Exception {
        MetaSearcher searcher = context.getBean(SearchManager.class).newSearcher(SearcherType.LUCENE, Geonet.File.SEARCH_LUCENE);
        Element parameters =  new Element(Jeeves.Elem.REQUEST);
        parameters.addContent(new Element(Geonet.IndexFieldNames.XLINK).addContent("*" + metadata.getUuid() + "*"));
        parameters.addContent(new Element(Geonet.SearchResult.BUILD_SUMMARY).setText("false"));
        parameters.addContent(new Element(SearchParameter.ISADMIN).addContent("true"));
        parameters.addContent(new Element(SearchParameter.ISTEMPLATE).addContent("y or n"));
        ServiceConfig config = new ServiceConfig();
        searcher.search(context, parameters, config);
        return searcher;
    }
        

    // --------------------------------------------------------------------------
    // ---
    // --- Metadata thumbnail API
    // ---
    // --------------------------------------------------------------------------

    private XmlSerializer getXmlSerializer() {
        return xmlSerializer;
    }

    /**
     * Removes a metadata.
     */
    @Override
    public synchronized void deleteMetadata(ServiceContext context, String metadataId) throws Exception {
        String uuid = metadataUtils.getMetadataUuid(metadataId);
        Metadata findOne = metadataRepository.findOne(metadataId);
        if (findOne != null) {
            boolean isMetadata = findOne.getDataInfo().getType() == MetadataType.METADATA;

            deleteMetadataFromDB(context, metadataId);

            // Notifies the metadata change to metatada notifier service
            if (isMetadata) {
                context.getBean(MetadataNotifierManager.class).deleteMetadata(metadataId, uuid, context);
            }
        }

        // --- update search criteria
        getSearchManager().delete(metadataId + "");
        // _entityManager.flush();
        // _entityManager.clear();
    }

    /**
     *
     * @param context
     * @param metadataId
     * @throws Exception
     */
    @Override
    public synchronized void deleteMetadataGroup(ServiceContext context, String metadataId) throws Exception {
        deleteMetadataFromDB(context, metadataId);
        // --- update search criteria
        getSearchManager().delete(metadataId + "");
    }

    /**
     * Creates a new metadata duplicating an existing template creating a random uuid.
     *
     * @param isTemplate
     * @param fullRightsForGroup
     */
    @Override
    public String createMetadata(ServiceContext context, String templateId, String groupOwner, String source, int owner, String parentUuid,
            String isTemplate, boolean fullRightsForGroup) throws Exception {

        return createMetadata(context, templateId, groupOwner, source, owner, parentUuid, isTemplate, fullRightsForGroup,
                UUID.randomUUID().toString());
    }

    /**
     * Creates a new metadata duplicating an existing template with an specified uuid.
     *
     * @param isTemplate
     * @param fullRightsForGroup
     */
    @Override
    public String createMetadata(ServiceContext context, String templateId, String groupOwner, String source, int owner, String parentUuid,
            String isTemplate, boolean fullRightsForGroup, String uuid) throws Exception {
        Metadata templateMetadata = metadataRepository.findOne(templateId);
        if (templateMetadata == null) {
            throw new IllegalArgumentException("Template id not found : " + templateId);
        }

        String schema = templateMetadata.getDataInfo().getSchemaId();
        String data = templateMetadata.getData();
        Element xml = Xml.loadString(data, false);
        if (templateMetadata.getDataInfo().getType() == MetadataType.METADATA) {
            xml = updateFixedInfo(schema, Optional.<Integer> absent(), uuid, xml, parentUuid, UpdateDatestamp.NO, context);
        }
        final Metadata newMetadata = new Metadata().setUuid(uuid);
        newMetadata.getDataInfo().setChangeDate(new ISODate()).setCreateDate(new ISODate()).setSchemaId(schema)
                .setType(MetadataType.lookup(isTemplate));
        newMetadata.getSourceInfo().setGroupOwner(Integer.valueOf(groupOwner)).setOwner(owner).setSourceId(source);

        // If there is a default category for the group, use it:
        Group group = groupRepository.findOne(Integer.valueOf(groupOwner));
        if (group.getDefaultCategory() != null) {
            newMetadata.getMetadataCategories().add(group.getDefaultCategory());
        }
        Collection<MetadataCategory> filteredCategories = Collections2.filter(templateMetadata.getMetadataCategories(),
                new Predicate<MetadataCategory>() {
                    @Override
                    public boolean apply(@Nullable MetadataCategory input) {
                        return input != null;
                    }
                });

        newMetadata.getMetadataCategories().addAll(filteredCategories);

        int finalId = insertMetadata(context, newMetadata, xml, false, true, true, UpdateDatestamp.YES, fullRightsForGroup, true).getId();

        return String.valueOf(finalId);
    }

    /**
     * Inserts a metadata into the database, optionally indexing it, and optionally applying automatic changes to it (update-fixed-info).
     *
     * @param context the context describing the user and service
     * @param schema XSD this metadata conforms to
     * @param metadataXml the metadata to store
     * @param uuid unique id for this metadata
     * @param owner user who owns this metadata
     * @param groupOwner group this metadata belongs to
     * @param source id of the origin of this metadata (harvesting source, etc.)
     * @param metadataType whether this metadata is a template
     * @param docType ?!
     * @param category category of this metadata
     * @param createDate date of creation
     * @param changeDate date of modification
     * @param ufo whether to apply automatic changes
     * @param index whether to index this metadata
     * @return id, as a string
     * @throws Exception hmm
     */
    @Override
    public String insertMetadata(ServiceContext context, String schema, Element metadataXml, String uuid, int owner, String groupOwner,
            String source, String metadataType, String docType, String category, String createDate, String changeDate, boolean ufo,
            boolean index) throws Exception {

        boolean notifyChange = true;

        if (source == null) {
            source = settingManager.getSiteId();
        }

        if (StringUtils.isBlank(metadataType)) {
            metadataType = MetadataType.METADATA.codeString;
        }
        final Metadata newMetadata = new Metadata().setUuid(uuid);
        final ISODate isoChangeDate = changeDate != null ? new ISODate(changeDate) : new ISODate();
        final ISODate isoCreateDate = createDate != null ? new ISODate(createDate) : new ISODate();
        newMetadata.getDataInfo().setChangeDate(isoChangeDate).setCreateDate(isoCreateDate).setSchemaId(schema).setDoctype(docType)
                .setRoot(metadataXml.getQualifiedName()).setType(MetadataType.lookup(metadataType));
        newMetadata.getSourceInfo().setOwner(owner).setSourceId(source);
        if (StringUtils.isNotEmpty(groupOwner)) {
            newMetadata.getSourceInfo().setGroupOwner(Integer.valueOf(groupOwner));
        }
        if (StringUtils.isNotEmpty(category)) {
            MetadataCategory metadataCategory = metadataCategoryRepository.findOneByName(category);
            if (metadataCategory == null) {
                throw new IllegalArgumentException("No category found with name: " + category);
            }
            newMetadata.getMetadataCategories().add(metadataCategory);
        } else if (StringUtils.isNotEmpty(groupOwner)) {
            // If the group has a default category, use it
            Group group = groupRepository.findOne(Integer.valueOf(groupOwner));
            if (group.getDefaultCategory() != null) {
                newMetadata.getMetadataCategories().add(group.getDefaultCategory());
            }
        }

        boolean fullRightsForGroup = false;

        int finalId = insertMetadata(context, newMetadata, metadataXml, notifyChange, index, ufo, UpdateDatestamp.NO, fullRightsForGroup,
                false).getId();

        return String.valueOf(finalId);
    }

    @Override
    public Metadata insertMetadata(ServiceContext context, Metadata newMetadata, Element metadataXml, boolean notifyChange, boolean index,
            boolean updateFixedInfo, UpdateDatestamp updateDatestamp, boolean fullRightsForGroup, boolean forceRefreshReaders)
            throws Exception {
        final String schema = newMetadata.getDataInfo().getSchemaId();

        // Check if the schema is allowed by settings
        String mdImportSetting = settingManager.getValue(Settings.METADATA_IMPORT_RESTRICT);
        if (mdImportSetting != null && !mdImportSetting.equals("")) {
            if(!newMetadata.getHarvestInfo().isHarvested() &&
                    newMetadata.getDataInfo().getType() == MetadataType.METADATA &&
                    !Arrays.asList(mdImportSetting.split(",")).contains(schema)) {
                throw new IllegalArgumentException(schema + " is not permitted in the database as a non-harvested metadata.  "
                        + "Apply a import stylesheet to convert file to allowed schemas");
            }
        }

        // --- force namespace prefix for iso19139 metadata
        setNamespacePrefixUsingSchemas(schema, metadataXml);

        if (updateFixedInfo && newMetadata.getDataInfo().getType() == MetadataType.METADATA) {
            String parentUuid = null;
            metadataXml = updateFixedInfo(schema, Optional.<Integer> absent(), newMetadata.getUuid(), metadataXml, parentUuid,
                    updateDatestamp, context);
        }

        // --- store metadata
        final Metadata savedMetadata = getXmlSerializer().insert(newMetadata, metadataXml, context);

        final String stringId = String.valueOf(savedMetadata.getId());
        String groupId = null;
        final Integer groupIdI = newMetadata.getSourceInfo().getGroupOwner();
        if (groupIdI != null) {
            groupId = String.valueOf(groupIdI);
        }
        metadataOperations.copyDefaultPrivForGroup(context, stringId, groupId, fullRightsForGroup);

        if (index) {
            metadataIndexer.indexMetadata(stringId, forceRefreshReaders, null);
        }

        if (notifyChange) {
            // Notifies the metadata change to metatada notifier service
            metadataUtils.notifyMetadataChange(metadataXml, stringId);
        }
        return savedMetadata;
    }

    /**
     * Retrieves a metadata (in xml) given its id; adds editing information if requested and validation errors if requested.
     *
     * @param forEditing Add extra element to build metadocument {@link EditLib#expandElements(String, Element)}
     * @param keepXlinkAttributes When XLinks are resolved in non edit mode, do not remove XLink attributes.
     */
    @Override
    public Element getMetadata(ServiceContext srvContext, String id, boolean forEditing, boolean withEditorValidationErrors,
            boolean keepXlinkAttributes) throws Exception {
        boolean doXLinks = getXmlSerializer().resolveXLinks();
        Element metadataXml = getXmlSerializer().selectNoXLinkResolver(id, false, forEditing);
        if (metadataXml == null)
            return null;

        String version = null;

        if (forEditing) { // copy in xlink'd fragments but leave xlink atts to editor
            if (doXLinks)
                Processor.processXLink(metadataXml, srvContext);
            String schema = metadataSchemaUtils.getMetadataSchema(id);

            // Inflate metadata
            Path inflateStyleSheet = metadataSchemaUtils.getSchemaDir(schema).resolve(Geonet.File.INFLATE_METADATA);
            if (Files.exists(inflateStyleSheet)) {
                // --- setup environment
                Element env = new Element("env");
                env.addContent(new Element("lang").setText(srvContext.getLanguage()));

                // add original metadata to result
                Element result = new Element("root");
                result.addContent(metadataXml);
                result.addContent(env);

                metadataXml = Xml.transform(result, inflateStyleSheet);
            }

            if (withEditorValidationErrors) {
                version = metadataValidator
                        .doValidate(srvContext.getUserSession(), schema, id, metadataXml, srvContext.getLanguage(), forEditing).two();
            } else {
                editLib.expandElements(schema, metadataXml);
                version = editLib.getVersionForEditing(schema, id, metadataXml);
            }
        } else {
            if (doXLinks) {
                if (keepXlinkAttributes) {
                    Processor.processXLink(metadataXml, srvContext);
                } else {
                    Processor.detachXLink(metadataXml, srvContext);
                }
            }
        }

        metadataXml.addNamespaceDeclaration(Edit.NAMESPACE);
        Element info = buildInfoElem(srvContext, id, version);
        metadataXml.addContent(info);

        metadataXml.detach();
        return metadataXml;
    }

    /**
     * Retrieves a metadata (in xml) given its id. Use this method when you must retrieve a metadata in the same transaction.
     */
    @Override
    public Element getMetadata(String id) throws Exception {
        Element md = getXmlSerializer().selectNoXLinkResolver(id, false, false);
        if (md == null)
            return null;
        md.detach();
        return md;
    }

    /**
     * For update of owner info.
     */
    @Override
    public synchronized void updateMetadataOwner(final int id, final String owner, final String groupOwner) throws Exception {
        metadataRepository.update(id, new Updater<Metadata>() {
            @Override
            public void apply(@Nonnull Metadata entity) {
                entity.getSourceInfo().setGroupOwner(Integer.valueOf(groupOwner));
                entity.getSourceInfo().setOwner(Integer.valueOf(owner));
            }
        });
    }

    /**
     * Updates a metadata record. Deletes validation report currently in session (if any). If user asks for validation the validation report
     * will be (re-)created then.
     *
     * @return metadata if the that was updated
     */
    @Override
    public synchronized Metadata updateMetadata(final ServiceContext context, final String metadataId, final Element md,
            final boolean validate, final boolean ufo, final boolean index, final String lang, final String changeDate,
            final boolean updateDateStamp) throws Exception {
        Element metadataXml = md;

        // when invoked from harvesters, session is null?
        UserSession session = context.getUserSession();
        if (session != null) {
            session.removeProperty(Geonet.Session.VALIDATION_REPORT + metadataId);
        }
        String schema = metadataSchemaUtils.getMetadataSchema(metadataId);
        if (ufo) {
            String parentUuid = null;
            Integer intId = Integer.valueOf(metadataId);

            final Metadata metadata = metadataRepository.findOne(metadataId);

            String uuid = null;

            if (schemaManager.getSchema(schema).isReadwriteUUID() && metadata.getDataInfo().getType() != MetadataType.SUB_TEMPLATE
                    && metadata.getDataInfo().getType() != MetadataType.TEMPLATE_OF_SUB_TEMPLATE) {
                uuid = metadataUtils.extractUUID(schema, metadataXml);
            }

            metadataXml = updateFixedInfo(schema, Optional.of(intId), uuid, metadataXml, parentUuid,
                    (updateDateStamp ? UpdateDatestamp.YES : UpdateDatestamp.NO), context);
        }

        // --- force namespace prefix for iso19139 metadata
        setNamespacePrefixUsingSchemas(schema, metadataXml);

        // Notifies the metadata change to metatada notifier service
        final Metadata metadata = metadataRepository.findOne(metadataId);

        String uuid = null;
        if (schemaManager.getSchema(schema).isReadwriteUUID() && metadata.getDataInfo().getType() != MetadataType.SUB_TEMPLATE
                && metadata.getDataInfo().getType() != MetadataType.TEMPLATE_OF_SUB_TEMPLATE) {
            uuid = metadataUtils.extractUUID(schema, metadataXml);
        }

        // --- write metadata to dbms
        getXmlSerializer().update(metadataId, metadataXml, changeDate, updateDateStamp, uuid, context);
        // Notifies the metadata change to metatada notifier service
        metadataUtils.notifyMetadataChange(metadataXml, metadataId);

        try {
            // --- do the validation last - it throws exceptions
            if (session != null && validate) {
                metadataValidator.doValidate(session, schema, metadataId, metadataXml, lang, false);
            }
        } finally {
            if (index) {
                // --- update search criteria
                metadataIndexer.indexMetadata(metadataId, true, null);
            }
        }

        if (metadata.getDataInfo().getType() == MetadataType.SUB_TEMPLATE) {
            if (!index) {
                metadataIndexer.indexMetadata(metadataId, true, null);
            }
            MetaSearcher searcher = searcherForReferencingMetadata(context, metadata);
            Map<Integer, Metadata> result = ((LuceneSearcher) searcher).getAllMdInfo(context, 500);
            for (Integer id : result.keySet()) {
                IndexingList list = context.getBean(IndexingList.class);
                list.add(id);
            }
        }
        // Return an up to date metadata record
        return metadataRepository.findOne(metadataId);
    }

    /**
     * TODO : buildInfoElem contains similar portion of code with indexMetadata
     */
    private Element buildInfoElem(ServiceContext context, String id, String version) throws Exception {
        Metadata metadata = metadataRepository.findOne(id);
        final MetadataDataInfo dataInfo = metadata.getDataInfo();
        String schema = dataInfo.getSchemaId();
        String createDate = dataInfo.getCreateDate().getDateAndTime();
        String changeDate = dataInfo.getChangeDate().getDateAndTime();
        String source = metadata.getSourceInfo().getSourceId();
        String isTemplate = dataInfo.getType().codeString;
        String title = dataInfo.getTitle();
        String uuid = metadata.getUuid();
        String isHarvested = "" + Constants.toYN_EnabledChar(metadata.getHarvestInfo().isHarvested());
        String harvestUuid = metadata.getHarvestInfo().getUuid();
        String popularity = "" + dataInfo.getPopularity();
        String rating = "" + dataInfo.getRating();
        String owner = "" + metadata.getSourceInfo().getOwner();
        String displayOrder = "" + dataInfo.getDisplayOrder();

        Element info = new Element(Edit.RootChild.INFO, Edit.NAMESPACE);

        addElement(info, Edit.Info.Elem.ID, id);
        addElement(info, Edit.Info.Elem.SCHEMA, schema);
        addElement(info, Edit.Info.Elem.CREATE_DATE, createDate);
        addElement(info, Edit.Info.Elem.CHANGE_DATE, changeDate);
        addElement(info, Edit.Info.Elem.IS_TEMPLATE, isTemplate);
        addElement(info, Edit.Info.Elem.TITLE, title);
        addElement(info, Edit.Info.Elem.SOURCE, source);
        addElement(info, Edit.Info.Elem.UUID, uuid);
        addElement(info, Edit.Info.Elem.IS_HARVESTED, isHarvested);
        addElement(info, Edit.Info.Elem.POPULARITY, popularity);
        addElement(info, Edit.Info.Elem.RATING, rating);
        addElement(info, Edit.Info.Elem.DISPLAY_ORDER, displayOrder);

        if (metadata.getHarvestInfo().isHarvested()) {
            if (harvestInfoProvider != null) {
                info.addContent(harvestInfoProvider.getHarvestInfo(harvestUuid, id, uuid));
            }
        }
        if (version != null) {
            addElement(info, Edit.Info.Elem.VERSION, version);
        }

        Map<String, Element> map = Maps.newHashMap();
        map.put(id, info);
        buildPrivilegesMetadataInfo(context, map);

        // add owner name
        User user = userRepository.findOne(owner);
        if (user != null) {
            String ownerName = user.getName();
            addElement(info, Edit.Info.Elem.OWNERNAME, ownerName);
        }

        for (MetadataCategory category : metadata.getMetadataCategories()) {
            addElement(info, Edit.Info.Elem.CATEGORY, category.getName());
        }

        // add subtemplates
        /*
         * -- don't add as we need to investigate indexing for the fields -- in the metadata table used here List subList =
         * getSubtemplates(dbms, schema); if (subList != null) { Element subs = new Element(Edit.Info.Elem.SUBTEMPLATES);
         * subs.addContent(subList); info.addContent(subs); }
         */

        // Add validity information
        List<MetadataValidation> validationInfo = metadataValidationRepository.findAllById_MetadataId(Integer.parseInt(id));
        if (validationInfo == null || validationInfo.size() == 0) {
            addElement(info, Edit.Info.Elem.VALID, "-1");
        } else {
            String isValid = "1";
            for (Object elem : validationInfo) {
                MetadataValidation vi = (MetadataValidation) elem;
                String type = vi.getId().getValidationType();
                if (!vi.isValid()) {
                    isValid = "0";
                }

                String ratio = "xsd".equals(type) ? "" : vi.getNumFailures() + "/" + vi.getNumTests();

                info.addContent(new Element(Edit.Info.Elem.VALID + "_details").addContent(new Element("type").setText(type)).addContent(
                        new Element("status").setText(vi.isValid() ? "1" : "0").addContent(new Element("ratio").setText(ratio))));
            }
            addElement(info, Edit.Info.Elem.VALID, isValid);
        }

        // add baseUrl of this site (from settings)
        String protocol = settingManager.getValue(Settings.SYSTEM_SERVER_PROTOCOL);
        String host = settingManager.getValue(Settings.SYSTEM_SERVER_HOST);
        String port = settingManager.getValue(Settings.SYSTEM_SERVER_PORT);
        if (port.equals("80")) {
            port = "";
        } else {
            port = ":" + port;
        }
        addElement(info, Edit.Info.Elem.BASEURL, protocol + "://" + host + port + baseURL);
        addElement(info, Edit.Info.Elem.LOCSERV, "/srv/en");
        return info;
    }

    /**
     * Update metadata record (not template) using update-fixed-info.xsl
     *
     * @param uuid If the metadata is a new record (not yet saved), provide the uuid for that record
     * @param updateDatestamp updateDatestamp is not used when running XSL transformation
     */
    @Override
    public Element updateFixedInfo(String schema, Optional<Integer> metadataId, String uuid, Element md, String parentUuid,
            UpdateDatestamp updateDatestamp, ServiceContext context) throws Exception {
        boolean autoFixing = settingManager.getValueAsBool(Settings.SYSTEM_AUTOFIXING_ENABLE, true);
        if (autoFixing) {
            if (Log.isDebugEnabled(Geonet.DATA_MANAGER)) {
                Log.debug(Geonet.DATA_MANAGER,
                        "Autofixing is enabled, trying update-fixed-info (updateDatestamp: " + updateDatestamp.name() + ")");
            }

            Metadata metadata = null;
            if (metadataId.isPresent()) {
                metadata = metadataRepository.findOne(metadataId.get());
                boolean isTemplate = metadata != null && metadata.getDataInfo().getType() == MetadataType.TEMPLATE;

                // don't process templates
                if (isTemplate) {
                    if (Log.isDebugEnabled(Geonet.DATA_MANAGER)) {
                        Log.debug(Geonet.DATA_MANAGER, "Not applying update-fixed-info for a template");
                    }
                    return md;
                }
            }

            String currentUuid = metadata != null ? metadata.getUuid() : null;
            String id = metadata != null ? metadata.getId() + "" : null;
            uuid = uuid == null ? currentUuid : uuid;

            // --- setup environment
            Element env = new Element("env");
            env.addContent(new Element("id").setText(id));
            env.addContent(new Element("uuid").setText(uuid));

            env.addContent(thesaurusManager.buildResultfromThTable(context));

            Element schemaLoc = new Element("schemaLocation");
            schemaLoc.setAttribute(schemaManager.getSchemaLocation(schema, context));
            env.addContent(schemaLoc);

            if (updateDatestamp == UpdateDatestamp.YES) {
                env.addContent(new Element("changeDate").setText(new ISODate().toString()));
            }
            if (parentUuid != null) {
                env.addContent(new Element("parentUuid").setText(parentUuid));
            }
            if (metadataId.isPresent()) {
                String metadataIdString = String.valueOf(metadataId.get());
                final Path resourceDir = Lib.resource.getDir(context, Params.Access.PRIVATE, metadataIdString);
                env.addContent(new Element("datadir").setText(resourceDir.toString()));
            }

            // add user information to env if user is authenticated (should be)
            Element elUser = new Element("user");
            UserSession usrSess = context.getUserSession();
            if (usrSess.isAuthenticated()) {
                String myUserId = usrSess.getUserId();
                User user = getApplicationContext().getBean(UserRepository.class).findOne(myUserId);
                if (user != null) {
                    Element elUserDetails = new Element("details");
                    elUserDetails.addContent(new Element("surname").setText(user.getSurname()));
                    elUserDetails.addContent(new Element("firstname").setText(user.getName()));
                    elUserDetails.addContent(new Element("organisation").setText(user.getOrganisation()));
                    elUserDetails.addContent(new Element("username").setText(user.getUsername()));
                    elUser.addContent(elUserDetails);
                    env.addContent(elUser);
                }
            }

            // add original metadata to result
            Element result = new Element("root");
            result.addContent(md);
            // add 'environment' to result
            env.addContent(new Element("siteURL").setText(settingManager.getSiteURL(context)));
            env.addContent(new Element("nodeURL").setText(settingManager.getNodeURL()));
            env.addContent(new Element("node").setText(context.getNodeId()));

            // Settings were defined as an XML starting with root named config
            // Only second level elements are defined (under system).
            List<?> config = settingManager.getAllAsXML(true).cloneContent();
            for (Object c : config) {
                Element settings = (Element) c;
                env.addContent(settings);
            }

            result.addContent(env);
            // apply update-fixed-info.xsl
            Path styleSheet = metadataSchemaUtils.getSchemaDir(schema).resolve(
                                metadata != null && metadata.getDataInfo().getType() == MetadataType.SUB_TEMPLATE ?
                                Geonet.File.UPDATE_FIXED_INFO_SUBTEMPLATE :
                                Geonet.File.UPDATE_FIXED_INFO);
            result = Xml.transform(result, styleSheet);
            return result;
        } else {
            if (Log.isDebugEnabled(Geonet.DATA_MANAGER)) {
                Log.debug(Geonet.DATA_MANAGER, "Autofixing is disabled, not applying update-fixed-info");
            }
            return md;
        }
    }

    /**
     * Updates all children of the selected parent. Some elements are protected in the children according to the stylesheet used in
     * xml/schemas/[SCHEMA]/update-child-from-parent-info.xsl.
     *
     * Children MUST be editable and also in the same schema of the parent. If not, child is not updated.
     *
     * @param srvContext service context
     * @param parentUuid parent uuid
     * @param children children
     * @param params parameters
     */
    @Override
    public Set<String> updateChildren(ServiceContext srvContext, String parentUuid, String[] children, Map<String, Object> params)
            throws Exception {
        String parentId = (String) params.get(Params.ID);
        String parentSchema = (String) params.get(Params.SCHEMA);

        // --- get parent metadata in read/only mode
        boolean forEditing = false, withValidationErrors = false, keepXlinkAttributes = false;
        Element parent = getMetadata(srvContext, parentId, forEditing, withValidationErrors, keepXlinkAttributes);

        Element env = new Element("update");
        env.addContent(new Element("parentUuid").setText(parentUuid));
        env.addContent(new Element("siteURL").setText(settingManager.getSiteURL(srvContext)));
        env.addContent(new Element("parent").addContent(parent));

        // Set of untreated children (out of privileges, different schemas)
        Set<String> untreatedChildSet = new HashSet<String>();

        // only get iso19139 records
        for (String childId : children) {

            // Check privileges
            if (!accessManager.canEdit(srvContext, childId)) {
                untreatedChildSet.add(childId);
                if (Log.isDebugEnabled(Geonet.DATA_MANAGER))
                    Log.debug(Geonet.DATA_MANAGER, "Could not update child (" + childId + ") because of privileges.");
                continue;
            }

            Element child = getMetadata(srvContext, childId, forEditing, withValidationErrors, keepXlinkAttributes);

            String childSchema = child.getChild(Edit.RootChild.INFO, Edit.NAMESPACE).getChildText(Edit.Info.Elem.SCHEMA);

            // Check schema matching. CHECKME : this suppose that parent and
            // child are in the same schema (even not profil different)
            if (!childSchema.equals(parentSchema)) {
                untreatedChildSet.add(childId);
                if (Log.isDebugEnabled(Geonet.DATA_MANAGER)) {
                    Log.debug(Geonet.DATA_MANAGER, "Could not update child (" + childId + ") because schema (" + childSchema
                            + ") is different from the parent one (" + parentSchema + ").");
                }
                continue;
            }

            if (Log.isDebugEnabled(Geonet.DATA_MANAGER))
                Log.debug(Geonet.DATA_MANAGER, "Updating child (" + childId + ") ...");

            // --- setup xml element to be processed by XSLT

            Element rootEl = new Element("root");
            Element childEl = new Element("child").addContent(child.detach());
            rootEl.addContent(childEl);
            rootEl.addContent(env.detach());

            // --- do an XSL transformation

            Path styleSheet = metadataSchemaUtils.getSchemaDir(parentSchema).resolve(Geonet.File.UPDATE_CHILD_FROM_PARENT_INFO);
            Element childForUpdate = Xml.transform(rootEl, styleSheet, params);

            getXmlSerializer().update(childId, childForUpdate, new ISODate().toString(), true, null, srvContext);

            // Notifies the metadata change to metatada notifier service
            metadataUtils.notifyMetadataChange(childForUpdate, childId);

            rootEl = null;
        }

        return untreatedChildSet;
    }

    // ---------------------------------------------------------------------------
    // ---
    // --- Static methods are for external modules like GAST to be able to use
    // --- them.
    // ---
    // ---------------------------------------------------------------------------

    /**
     * Add privileges information about metadata record which depends on context and usually could not be stored in db or Lucene index
     * because depending on the current user or current client IP address.
     *
     * @param mdIdToInfoMap a map from the metadata Id -> the info element to which the privilege information should be added.
     */
    @VisibleForTesting
    @Override
    public void buildPrivilegesMetadataInfo(ServiceContext context, Map<String, Element> mdIdToInfoMap) throws Exception {
        Collection<Integer> metadataIds = Collections2.transform(mdIdToInfoMap.keySet(), new Function<String, Integer>() {
            @Nullable
            @Override
            public Integer apply(String input) {
                return Integer.valueOf(input);
            }
        });
        Specification<OperationAllowed> operationAllowedSpec = OperationAllowedSpecs.hasMetadataIdIn(metadataIds);

        final Collection<Integer> allUserGroups = accessManager.getUserGroups(context.getUserSession(), context.getIpAddress(), false);
        final SetMultimap<Integer, ReservedOperation> operationsPerMetadata = loadOperationsAllowed(context,
                where(operationAllowedSpec).and(OperationAllowedSpecs.hasGroupIdIn(allUserGroups)));
        final Set<Integer> visibleToAll = loadOperationsAllowed(context,
                where(operationAllowedSpec).and(OperationAllowedSpecs.isPublic(ReservedOperation.view))).keySet();
        final Set<Integer> downloadableByGuest = loadOperationsAllowed(context,
                where(operationAllowedSpec).and(OperationAllowedSpecs.hasGroupId(ReservedGroup.guest.getId()))
                        .and(OperationAllowedSpecs.hasOperation(ReservedOperation.download))).keySet();
        final Map<Integer, MetadataSourceInfo> allSourceInfo = metadataRepository
                .findAllSourceInfo(MetadataSpecs.hasMetadataIdIn(metadataIds));

        for (Map.Entry<String, Element> entry : mdIdToInfoMap.entrySet()) {
            Element infoEl = entry.getValue();
            final Integer mdId = Integer.valueOf(entry.getKey());
            MetadataSourceInfo sourceInfo = allSourceInfo.get(mdId);
            Set<ReservedOperation> operations = operationsPerMetadata.get(mdId);
            if (operations == null) {
                operations = Collections.emptySet();
            }

            boolean isOwner = accessManager.isOwner(context, sourceInfo);

            if (isOwner) {
                operations = Sets.newHashSet(Arrays.asList(ReservedOperation.values()));
            }

            if (isOwner || operations.contains(ReservedOperation.editing)) {
                addElement(infoEl, Edit.Info.Elem.EDIT, "true");
            }

            if (isOwner) {
                addElement(infoEl, Edit.Info.Elem.OWNER, "true");
            }

            addElement(infoEl, Edit.Info.Elem.IS_PUBLISHED_TO_ALL, visibleToAll.contains(mdId));
            addElement(infoEl, ReservedOperation.view.name(), operations.contains(ReservedOperation.view));
            addElement(infoEl, ReservedOperation.notify.name(), operations.contains(ReservedOperation.notify));
            addElement(infoEl, ReservedOperation.download.name(), operations.contains(ReservedOperation.download));
            addElement(infoEl, ReservedOperation.dynamic.name(), operations.contains(ReservedOperation.dynamic));
            addElement(infoEl, ReservedOperation.featured.name(), operations.contains(ReservedOperation.featured));

            if (!operations.contains(ReservedOperation.download)) {
                addElement(infoEl, Edit.Info.Elem.GUEST_DOWNLOAD, downloadableByGuest.contains(mdId));
            }
        }
    }

    private SetMultimap<Integer, ReservedOperation> loadOperationsAllowed(ServiceContext context,
            Specification<OperationAllowed> operationAllowedSpec) {
        final OperationAllowedRepository operationAllowedRepo = context.getBean(OperationAllowedRepository.class);
        List<OperationAllowed> operationsAllowed = operationAllowedRepo.findAll(operationAllowedSpec);
        SetMultimap<Integer, ReservedOperation> operationsPerMetadata = HashMultimap.create();
        for (OperationAllowed allowed : operationsAllowed) {
            final OperationAllowedId id = allowed.getId();
            operationsPerMetadata.put(id.getMetadataId(), ReservedOperation.lookup(id.getOperationId()));
        }
        return operationsPerMetadata;
    }

    /**
     *
     * @param md
     * @throws Exception
     */
    private void setNamespacePrefixUsingSchemas(String schema, Element md) throws Exception {
        // --- if the metadata has no namespace or already has a namespace prefix
        // --- then we must skip this phase
        Namespace ns = md.getNamespace();
        if (ns == Namespace.NO_NAMESPACE)
            return;

        MetadataSchema mds = schemaManager.getSchema(schema);

        // --- get the namespaces and add prefixes to any that are
        // --- default (ie. prefix is '') if namespace match one of the schema
        ArrayList<Namespace> nsList = new ArrayList<Namespace>();
        nsList.add(ns);
        @SuppressWarnings("unchecked")
        List<Namespace> additionalNamespaces = md.getAdditionalNamespaces();
        nsList.addAll(additionalNamespaces);
        for (Object aNsList : nsList) {
            Namespace aNs = (Namespace) aNsList;
            if (aNs.getPrefix().equals("")) { // found default namespace
                String prefix = mds.getPrefix(aNs.getURI());
                if (prefix == null) {
                    Log.warning(Geonet.DATA_MANAGER, "Metadata record contains a default namespace " + aNs.getURI()
                            + " (with no prefix) which does not match any " + schema + " schema's namespaces.");
                }
                ns = Namespace.getNamespace(prefix, aNs.getURI());
                metadataValidator.setNamespacePrefix(md, ns);
                if (!md.getNamespace().equals(ns)) {
                    md.removeNamespaceDeclaration(aNs);
                    md.addNamespaceDeclaration(ns);
                }
            }
        }
    }

    /**
     *
     * @param root
     * @param name
     * @param value
     */
    private static void addElement(Element root, String name, Object value) {
        root.addContent(new Element(name).setText(value == null ? "" : value.toString()));
    }
}