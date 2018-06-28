package amtgroup.devinfra.crowdldap.component.crowd.query.util;

import org.apache.directory.shared.ldap.constants.SchemaConstants;
import org.apache.directory.shared.ldap.exception.LdapInvalidDnException;
import org.apache.directory.shared.ldap.name.RDN;

/**
 * @author Vitaly Ogoltsov
 */
public class CrowdLdapConstants {

    public static final RDN GROUPS_RDN;
    public static final RDN USERS_RDN;

    static {
        try {
            GROUPS_RDN = new RDN(SchemaConstants.OU_AT, "groups");
            USERS_RDN = new RDN(SchemaConstants.OU_AT, "users");
        } catch (LdapInvalidDnException e) {
            throw new RuntimeException(e);
        }
    }

}