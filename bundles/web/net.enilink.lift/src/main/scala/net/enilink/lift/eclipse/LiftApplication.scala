package net.enilink.lift.eclipse

import org.eclipse.equinox.app.IApplication
import org.eclipse.equinox.app.IApplicationContext

class LiftApplication extends IApplication {
  def start(context: IApplicationContext) = IApplicationContext.EXIT_ASYNC_RESULT
  def stop {}
}