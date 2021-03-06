package amtgroup.devinfra.crowdldap.component.crowd.ldap;

import amtgroup.devinfra.crowdldap.component.crowd.config.CrowdLdapProperties;
import amtgroup.devinfra.crowdldap.component.crowd.util.CrowdLdapConstants;
import amtgroup.devinfra.crowdldap.component.crowd.util.CrowdLdapFilter;
import amtgroup.devinfra.crowdldap.util.exception.ExceptionMessageUtils;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.directory.server.core.DirectoryService;
import org.apache.directory.server.core.entry.ClonedServerEntry;
import org.apache.directory.server.core.filtering.BaseEntryFilteringCursor;
import org.apache.directory.server.core.filtering.EntryFilteringCursor;
import org.apache.directory.server.core.interceptor.context.AddOperationContext;
import org.apache.directory.server.core.interceptor.context.BindOperationContext;
import org.apache.directory.server.core.interceptor.context.DeleteOperationContext;
import org.apache.directory.server.core.interceptor.context.EntryOperationContext;
import org.apache.directory.server.core.interceptor.context.ListOperationContext;
import org.apache.directory.server.core.interceptor.context.LookupOperationContext;
import org.apache.directory.server.core.interceptor.context.ModifyOperationContext;
import org.apache.directory.server.core.interceptor.context.MoveAndRenameOperationContext;
import org.apache.directory.server.core.interceptor.context.MoveOperationContext;
import org.apache.directory.server.core.interceptor.context.RenameOperationContext;
import org.apache.directory.server.core.interceptor.context.SearchOperationContext;
import org.apache.directory.server.core.interceptor.context.UnbindOperationContext;
import org.apache.directory.server.core.partition.AbstractPartition;
import org.apache.directory.shared.ldap.constants.SchemaConstants;
import org.apache.directory.shared.ldap.cursor.ListCursor;
import org.apache.directory.shared.ldap.entry.DefaultServerEntry;
import org.apache.directory.shared.ldap.entry.ServerEntry;
import org.apache.directory.shared.ldap.exception.LdapInvalidDnException;
import org.apache.directory.shared.ldap.name.DN;
import org.apache.directory.shared.ldap.schema.SchemaManager;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.naming.OperationNotSupportedException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * @author Vitaly Ogoltsov
 */
@Component
@Slf4j
public class CrowdLdapPartition extends AbstractPartition {

    private final CrowdLdapRepository crowdLdapRepository;

    private final CrowdLdapFilter crowdLdapFilter;

    private final DirectoryService directoryService;

    @Getter
    @Setter
    private String id;
    @Getter
    private String suffix;
    @Getter
    private DN suffixDn;
    @Getter
    @Setter
    private SchemaManager schemaManager;


    private ServerEntry domainEntry;
    private ServerEntry groupsEntry;
    private ServerEntry usersEntry;


    public CrowdLdapPartition(CrowdLdapProperties crowdLdapProperties,
                              CrowdLdapRepository crowdLdapRepository,
                              CrowdLdapFilter crowdLdapFilter,
                              DirectoryService directoryService) throws LdapInvalidDnException {

        this.crowdLdapRepository = crowdLdapRepository;
        this.crowdLdapFilter = crowdLdapFilter;
        this.directoryService = directoryService;
        setId("crowd");
        setSuffix(crowdLdapProperties.getSuffix());
        setSchemaManager(directoryService.getSchemaManager());
    }


    @PostConstruct
    void init() throws Exception {
        this.initialize();
        directoryService.addPartition(this);
        crowdLdapRepository.sync();
    }


    @Override
    public void setSuffix(String suffix) throws LdapInvalidDnException {
        DN newSuffixDn = new DN(suffix);
        if (!Objects.equals(newSuffixDn.getRdn().getUpType(), SchemaConstants.DC_AT)) {
            throw new IllegalArgumentException("Partition suffix should be of type 'dc'");
        }
        this.suffixDn = newSuffixDn;
        this.suffix = suffix;
    }

    @Override
    protected void doInit() throws Exception {
        log.trace("doInit()");
        this.suffixDn.normalize(
                this.schemaManager
                        .getRegistries()
                        .getAttributeTypeRegistry()
                        .getNormalizerMapping()
        );

        this.domainEntry = new DefaultServerEntry(
                this.schemaManager,
                this.suffixDn
        );
        this.domainEntry.put(SchemaConstants.OBJECT_CLASS_AT, SchemaConstants.TOP_OC, SchemaConstants.DOMAIN_OC);
        this.domainEntry.put(this.domainEntry.getDn().getRdn().getUpType(), this.domainEntry.getDn().getRdn().getUpValue());
        this.domainEntry.put("description", "Crowd Domain");

        this.groupsEntry = new DefaultServerEntry(
                this.schemaManager,
                new DN().addAll(this.suffixDn).add(CrowdLdapConstants.GROUPS_RDN)
        );
        this.groupsEntry.put(SchemaConstants.OBJECT_CLASS_AT, SchemaConstants.TOP_OC, SchemaConstants.ORGANIZATIONAL_UNIT_OC);
        this.groupsEntry.put(this.groupsEntry.getDn().getRdn().getUpType(), this.groupsEntry.getDn().getRdn().getUpValue());
        this.groupsEntry.put("description", "Crowd Groups");

        this.usersEntry = new DefaultServerEntry(
                this.schemaManager,
                new DN().addAll(this.suffixDn).add(CrowdLdapConstants.USERS_RDN)
        );
        this.usersEntry.put(SchemaConstants.OBJECT_CLASS_AT, SchemaConstants.TOP_OC, SchemaConstants.ORGANIZATIONAL_UNIT_OC);
        this.usersEntry.put(this.usersEntry.getDn().getRdn().getUpType(), this.usersEntry.getDn().getRdn().getUpValue());
        this.usersEntry.put("description", "Crowd Users");
    }

    @Override
    protected void doDestroy() {
        log.trace("doDestroy()");
        this.domainEntry = null;
        this.groupsEntry = null;
        this.usersEntry = null;
    }

    @Override
    public boolean hasEntry(EntryOperationContext entryContext) {
        log.trace("hasEntry(): {}", entryContext);
        ServerEntry lookupResult = doLookup(entryContext.getDn());
        log.trace("hasEntry(): result = {}", lookupResult);
        return lookupResult != null;
    }

    @Override
    public ClonedServerEntry lookup(LookupOperationContext lookupContext) {
        log.trace("lookup(): {}", lookupContext);
        ServerEntry lookupResult = doLookup(lookupContext.getDn());
        log.trace("lookup(): result = {}", lookupResult);
        return lookupResult != null ? new ClonedServerEntry(lookupResult) : null;
    }

    private ServerEntry doLookup(DN lookupDn) {
        // domain entry
        if (StringUtils.equalsIgnoreCase(lookupDn.getName(), this.domainEntry.getDn().getName())) {
            return this.domainEntry;
        }
        // groups top entry
        if (StringUtils.equalsIgnoreCase(lookupDn.getName(), this.groupsEntry.getDn().getName())) {
            return this.groupsEntry;
        }
        // users top entry
        if (StringUtils.equalsIgnoreCase(lookupDn.getName(), this.usersEntry.getDn().getName())) {
            return this.usersEntry;
        }
        // lookup group
        if (StringUtils.endsWithIgnoreCase(lookupDn.getName(), this.groupsEntry.getDn().getName())) {
            DN groupDn = lookupDn.getSuffix(this.groupsEntry.getDn().size());
            if (groupDn.size() == 1) {
                // find group by id
                return this.crowdLdapRepository.findGroupEntryById(groupDn.getRdn())
                        .orElse(null);
            }
        }
        // lookup user
        if (StringUtils.endsWithIgnoreCase(lookupDn.getName(), this.usersEntry.getDn().getName())) {
            DN userDn = lookupDn.getSuffix(this.usersEntry.getDn().size());
            if (userDn.size() == 1) {
                // find user by id
                return this.crowdLdapRepository.findUserEntryById(userDn.getRdn())
                        .orElse(null);
            }
        }
        // nothing here
        return null;
    }

    @Override
    public EntryFilteringCursor search(SearchOperationContext searchContext) {
        log.trace("search(): {}", searchContext);
        DN searchDn = searchContext.getDn();
        List<ServerEntry> searchResults = new ArrayList<>();
        // domain entry
        if (StringUtils.equalsIgnoreCase(searchDn.getName(), this.domainEntry.getDn().getName())) {
            switch (searchContext.getScope()) {
                case OBJECT:
                    searchResults.add(this.domainEntry);
                    break;
                case SUBTREE:
                    searchResults.addAll(this.crowdLdapRepository.findAllGroupEntries());
                    searchResults.addAll(this.crowdLdapRepository.findAllUserEntries());
                case ONELEVEL:
                    searchResults.add(this.groupsEntry);
                    searchResults.add(this.usersEntry);
                    break;
            }
        }
        // groups top entry
        if (StringUtils.equalsIgnoreCase(searchDn.getName(), this.groupsEntry.getDn().getName())) {
            switch (searchContext.getScope()) {
                case OBJECT:
                    searchResults.add(this.groupsEntry);
                    break;
                case SUBTREE:
                case ONELEVEL:
                    searchResults.addAll(this.crowdLdapRepository.findAllGroupEntries());
                    break;
            }
        }
        // users top entry
        if (StringUtils.equalsIgnoreCase(searchDn.getName(), this.usersEntry.getDn().getName())) {
            switch (searchContext.getScope()) {
                case OBJECT:
                    searchResults.add(this.usersEntry);
                    break;
                case SUBTREE:
                case ONELEVEL:
                    searchResults.addAll(this.crowdLdapRepository.findAllUserEntries());
                    break;
            }
        }
        // single group entry
        if (StringUtils.equalsIgnoreCase(searchDn.getPrefix(searchDn.size() - 1).getName(), this.groupsEntry.getDn().getName())) {
            switch (searchContext.getScope()) {
                case OBJECT:
                    this.crowdLdapRepository.findGroupEntryById(searchDn.getRdn())
                            .ifPresent(searchResults::add);
                    break;
            }
        }
        // single user entry
        if (StringUtils.equalsIgnoreCase(searchDn.getPrefix(searchDn.size() - 1).getName(), this.usersEntry.getDn().getName())) {
            switch (searchContext.getScope()) {
                case OBJECT:
                    this.crowdLdapRepository.findUserEntryById(searchDn.getRdn())
                            .ifPresent(searchResults::add);
                    break;
            }
        }
        // filter results
        Optional<Predicate<ServerEntry>> filter = crowdLdapFilter.of(searchContext.getFilter());
        if (filter.isPresent()) {
            searchResults = searchResults.stream()
                    .filter(filter.get())
                    .collect(Collectors.toList());
        }
        log.trace("search(): {} results found", searchResults.size());
        // return result
        return new BaseEntryFilteringCursor(
                new ListCursor<>(searchResults),
                searchContext
        );
    }

    @Override
    public EntryFilteringCursor list(ListOperationContext opContext) {
        log.trace("list(): {}", opContext);
        return null;
    }

    @Override
    public void bind(BindOperationContext opContext) {
        log.trace("bind(): {}", opContext);
        // no-op
    }

    @Override
    public void unbind(UnbindOperationContext opContext) {
        log.trace("unbind(): {}", opContext);
        // no-op
    }

    @Override
    public void sync() {
        log.trace("sync()");
        // read-only partition - nothing to flush
    }

    @Override
    public void add(AddOperationContext opContext) throws OperationNotSupportedException {
        log.trace("add(): {}", opContext);
        throw new OperationNotSupportedException(ExceptionMessageUtils.getMessage(OperationNotSupportedException.class.getSimpleName()));
    }

    @Override
    public void delete(DeleteOperationContext opContext) throws OperationNotSupportedException {
        log.trace("delete(): {}", opContext);
        throw new OperationNotSupportedException(ExceptionMessageUtils.getMessage(OperationNotSupportedException.class.getSimpleName()));
    }

    @Override
    public void modify(ModifyOperationContext opContext) throws OperationNotSupportedException {
        log.trace("modify(): {}", opContext);
        throw new OperationNotSupportedException(ExceptionMessageUtils.getMessage(OperationNotSupportedException.class.getSimpleName()));
    }

    @Override
    public void move(MoveOperationContext opContext) throws OperationNotSupportedException {
        log.trace("move(): {}", opContext);
        throw new OperationNotSupportedException(ExceptionMessageUtils.getMessage(OperationNotSupportedException.class.getSimpleName()));
    }

    @Override
    public void rename(RenameOperationContext opContext) throws OperationNotSupportedException {
        log.trace("rename(): {}", opContext);
        throw new OperationNotSupportedException(ExceptionMessageUtils.getMessage(OperationNotSupportedException.class.getSimpleName()));
    }

    @Override
    public void moveAndRename(MoveAndRenameOperationContext opContext) throws OperationNotSupportedException {
        log.trace("moveAndRename(): {}", opContext);
        throw new OperationNotSupportedException(ExceptionMessageUtils.getMessage(OperationNotSupportedException.class.getSimpleName()));
    }

}
