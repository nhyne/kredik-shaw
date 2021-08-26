
object Main {
  def main(args: Array[String]): Unit = {
    println("in main")
  }

  // linked in user model
  // should be able to model connections as well
  // somewhat realistic

  // the Seq here could be a linked list or a List which could have O(n) or O(1) runtime for accessing
  final case class User(name: String, currentPosition: Position, connections: () => Set[User], jobHistory: Seq[Position], contactInfo: ContactInfo)


  final case class ContactInfo(primaryEmail: String, primaryPhone: String, additionalEmails: Set[String], additionalPhoneNumbers: Set[String])

  /*
  should do something liek
  type Email = String
  def apply(input: String): Either[E, Email]
   */

  final case class Position(jobTitle: String, jobDescription: String, company: Company, startTime: java.time.YearMonth, endTime: PositionEnd)

  sealed trait PositionEnd
  object PositionEnd {
    final case class Ended(time: java.time.YearMonth)
    case object Present
  }

  final case class Company(name: String)

  type NonEmptySet[A] = (A, Set[A])

  /*
  can be useful to know more about the java time abstractions -- should definitely do this
  when creating data types, use a concrete collection
    -- accept interfaces but provide concrete
  investigate the use of new types -- new types with smart constructors
   */



  // recursively clear all user job history and all users who they are connected to
  def clearHistory(user: User, depth: Int): User  = {
    user.copy(jobHistory = Seq.empty, connections = if (depth == 0) user.connections else {
      () => user.connections().map(clearHistory(_, depth - 1))
    })
  }

  /*
  new collections do not preserve the laziness of the

   */
}

