package net.enilink.platform.workbench;

import net.enilink.komma.model.IModelSet;
import net.enilink.platform.core.UseService;
import java.io.Serial;

import org.eclipse.rap.rwt.internal.lifecycle.PhaseEvent;
import org.eclipse.rap.rwt.internal.lifecycle.PhaseId;
import org.eclipse.rap.rwt.internal.lifecycle.PhaseListener;

public class UnitOfWorkPhaseListener implements PhaseListener {
	@Serial
	private static final long serialVersionUID = -4199229590183450740L;

	@Override
	public void beforePhase(PhaseEvent event) {
		if (event.getPhaseId().equals(PhaseId.PREPARE_UI_ROOT)) {
			new UseService<IModelSet, Void>(IModelSet.class) {
				@Override
				protected Void withService(IModelSet modelSet) {
					modelSet.getUnitOfWork().begin();
					return null;
				}
			};
		}
	}

	@Override
	public void afterPhase(PhaseEvent event) {
		if (event.getPhaseId().equals(PhaseId.RENDER)) {
			new UseService<IModelSet, Void>(IModelSet.class) {
				@Override
				protected Void withService(IModelSet modelSet) {
					modelSet.getUnitOfWork().end();
					return null;
				}
			};
		}
	}

	@Override
	public PhaseId getPhaseId() {
		return PhaseId.ANY;
	}

}
