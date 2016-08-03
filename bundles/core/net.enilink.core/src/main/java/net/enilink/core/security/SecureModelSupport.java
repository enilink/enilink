package net.enilink.core.security;

import net.enilink.composition.annotations.Precedes;
import net.enilink.komma.core.URI;
import net.enilink.komma.model.IModel;
import net.enilink.komma.model.base.ModelSupport;

@Precedes(ModelSupport.class)
public abstract class SecureModelSupport implements IModel.Internal {
	@Override
	public boolean demandLoadImport(URI imported) {
		return SecurityUtil.isMemberOf(getModelSet().getMetaDataManager(),
				SecurityUtil.ADMINISTRATOR_GROUP);
	}
}
