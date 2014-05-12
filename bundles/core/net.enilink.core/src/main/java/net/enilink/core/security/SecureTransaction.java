package net.enilink.core.security;

import net.enilink.komma.core.ITransaction;

class SecureTransaction implements ITransaction {
	final ITransaction delegate;

	public SecureTransaction(ITransaction delegate) {
		this.delegate = delegate;
	}

	@Override
	public void begin() {
		delegate.begin();
	}

	@Override
	public void commit() {
		delegate.commit();
	}

	@Override
	public void rollback() {
		delegate.rollback();
	}

	@Override
	public void setRollbackOnly() {
		delegate.setRollbackOnly();

	}

	@Override
	public boolean getRollbackOnly() {
		return delegate.getRollbackOnly();
	}

	@Override
	public boolean isActive() {
		return delegate.isActive();
	}

}