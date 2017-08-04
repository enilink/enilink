package net.enilink.core.security;

import org.aopalliance.intercept.MethodInvocation;

import net.enilink.composition.annotations.ParameterTypes;
import net.enilink.komma.core.URI;
import net.enilink.komma.model.IModel;

public abstract class SecureModelSupport implements IModel.Internal {
	@ParameterTypes(URI.class)
	public boolean demandLoadImport(MethodInvocation invocation) {
		return SecurityUtil.SYSTEM_USER.equals(SecurityUtil.getUser())
				|| SecurityUtil.isMemberOf(getModelSet().getMetaDataManager(), SecurityUtil.ADMINISTRATORS_GROUP);
	}
}