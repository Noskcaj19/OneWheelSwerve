// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package frc.robot.subsystems;

import com.ctre.phoenix.sensors.AbsoluteSensorRange;
import com.ctre.phoenix.sensors.CANCoder;
import com.ctre.phoenix.sensors.CANCoderConfiguration;
import com.ctre.phoenix.sensors.CANCoderStatusFrame;
import com.ctre.phoenix.sensors.SensorInitializationStrategy;
import com.ctre.phoenix.sensors.SensorTimeBase;
import com.revrobotics.CANSparkMax;
import com.revrobotics.CANSparkMax.ControlType;
import com.revrobotics.CANSparkMaxLowLevel;
import com.revrobotics.CANSparkMaxLowLevel.MotorType;
import com.revrobotics.RelativeEncoder;
import com.revrobotics.SparkMaxPIDController;

import edu.wpi.first.math.controller.PIDController;
import edu.wpi.first.math.controller.SimpleMotorFeedforward;
import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.math.kinematics.SwerveModulePosition;
import edu.wpi.first.math.kinematics.SwerveModuleState;
import edu.wpi.first.math.trajectory.TrapezoidProfile;
import edu.wpi.first.math.util.Units;
import edu.wpi.first.wpilibj.shuffleboard.Shuffleboard;
import frc.robot.Constants;
import frc.robot.Constants.ModuleConstants;

public class SwerveModule {
  public final CANSparkMax m_driveMotor;
  public final CANSparkMax m_turningMotor;

  private final RelativeEncoder m_driveEncoder;
  private final RelativeEncoder m_integratedTurningEncoder;
  private final CANCoder m_absoluteEncoder;

  private final PIDController m_drivePIDController = new PIDController(.1, 0, 0);

  private final SimpleMotorFeedforward m_turnFF = new SimpleMotorFeedforward(0.13943, 0.39686, 0.015295);

  // private final PIDController m_turningPIDController = new PIDController(
  // 0.13, 0, 0);
  // Using a TrapezoidProfile PIDController to allow for smooth turning
  private final PIDController m_turningPIDController = new PIDController(
      // 1.0, 0, 0.);
      1.0 / (Math.PI / 2), 0, .0);
  // private final ProfiledPIDController m_turningPIDController = new
  // ProfiledPIDController(
  // 0.064059,0,0.000,
  // new TrapezoidProfile.Constraints(
  // Math.toDegrees(ModuleConstants.kMaxModuleAngularSpeedRadiansPerSecond),
  // Math.toDegrees(ModuleConstants.kMaxModuleAngularAccelerationRadiansPerSecondSquared)));
  private SparkMaxPIDController pidController;

  private final TrapezoidProfile.Constraints m_turnConstraints = new TrapezoidProfile.Constraints(
      Float.POSITIVE_INFINITY,
      200 * 2);
  private TrapezoidProfile.State m_lastProfiledReference = new TrapezoidProfile.State();
  private int turningEncoderId;

  /**
   * Constructs a SwerveModule.
   *
   * @param driveMotorChannel      The channel of the drive motor.
   * @param turningMotorChannel    The channel of the turning motor.
   * @param turningEncoderChannel  The channels of the turning encoder.
   * @param driveEncoderReversed   Whether the drive encoder is reversed.
   * @param turningEncoderReversed Whether the turning encoder is reversed.
   */
  public SwerveModule(
      int driveMotorId,
      int turningMotorId,
      int turningEncoderId,
      boolean driveEncoderReversed,
      boolean turningEncoderReversed,
      Rotation2d magnetOffset, boolean enable) {
    m_driveMotor = new CANSparkMax(driveMotorId, MotorType.kBrushless);
    m_turningMotor = new CANSparkMax(turningMotorId, MotorType.kBrushless);
    m_turningMotor.restoreFactoryDefaults();

    m_absoluteEncoder = new CANCoder(turningEncoderId);

    var config = new CANCoderConfiguration();
    config.absoluteSensorRange = AbsoluteSensorRange.Signed_PlusMinus180;
    config.initializationStrategy = SensorInitializationStrategy.BootToAbsolutePosition;
    config.sensorTimeBase = SensorTimeBase.PerSecond;
    config.magnetOffsetDegrees = magnetOffset.getDegrees();
    config.sensorDirection = true;// !turningEncoderReversed;

    m_absoluteEncoder.configAllSettings(config, 250);
    m_absoluteEncoder.setStatusFramePeriod(CANCoderStatusFrame.SensorData, 100, 250);

    // #region Motor controller setup
    // m_driveMotor.setInverted(driveEncoderReversed);
    // m_turningMotor.setInverted(turningEncoderReversed);

    m_turningMotor.setSmartCurrentLimit(20);
    m_driveMotor.setSmartCurrentLimit(80);

    m_driveMotor.setPeriodicFramePeriod(CANSparkMaxLowLevel.PeriodicFrame.kStatus0, 100);
    m_driveMotor.setPeriodicFramePeriod(CANSparkMaxLowLevel.PeriodicFrame.kStatus1, 20);
    m_driveMotor.setPeriodicFramePeriod(CANSparkMaxLowLevel.PeriodicFrame.kStatus2, 20);
    // Set neutral mode
    m_driveMotor.setIdleMode(CANSparkMax.IdleMode.kBrake);

    m_turningMotor.setPeriodicFramePeriod(CANSparkMaxLowLevel.PeriodicFrame.kStatus0, 10);
    m_turningMotor.setPeriodicFramePeriod(CANSparkMaxLowLevel.PeriodicFrame.kStatus1, 20);
    m_turningMotor.setPeriodicFramePeriod(CANSparkMaxLowLevel.PeriodicFrame.kStatus2, 50);
    // Set neutral mode
    m_turningMotor.setIdleMode(CANSparkMax.IdleMode.kCoast);
    // #endregion

    m_driveEncoder = m_driveMotor.getEncoder();
    var positionConversionFactor = Math.PI * Constants.kModuleType.getWheelDiameter()
        * Constants.kModuleType.getDriveReduction();
    m_driveEncoder.setPositionConversionFactor(positionConversionFactor);
    m_driveEncoder.setVelocityConversionFactor(positionConversionFactor / 60);

    m_integratedTurningEncoder = m_turningMotor.getEncoder();

    m_integratedTurningEncoder
        .setPositionConversionFactor(Math.toRadians(ModuleConstants.kTurningEncoderDegreesPerPulse));
    m_integratedTurningEncoder
        .setVelocityConversionFactor(Math.toRadians(ModuleConstants.kTurningEncoderDegreesPerPulse) / 60);
    this.turningEncoderId = turningEncoderId;
    m_integratedTurningEncoder.setPosition(Units.degreesToRadians(m_absoluteEncoder.getAbsolutePosition()));

    m_turningPIDController.enableContinuousInput(-Math.PI, Math.PI);

    m_lastProfiledReference = new TrapezoidProfile.State(m_integratedTurningEncoder.getPosition(),
        m_integratedTurningEncoder.getVelocity());

    pidController = m_turningMotor.getPIDController();
    pidController.setP(1.0);
    pidController.setI(0.0);
    pidController.setD(0.1);
    // pidController.setFF(1.534);
    pidController.setOutputRange(-1, 1);

    // Shuffleboard.getTab("Debug").addDouble("Turn Output Raw", () ->
    // m_turningMotor.get());
    // Shuffleboard.getTab("Debug").addDouble("Drive Output Raw", () ->
    // m_driveMotor.get());
    Shuffleboard.getTab("Debug")
        .addDouble("Measured Abs rotation" + turningEncoderId,
            () -> Units.degreesToRadians(m_absoluteEncoder.getAbsolutePosition()));
    // Shuffleboard.getTab("Debug").addDouble("Integrated encoder", () ->
    // m_integratedTurningEncoder.getPosition());
  }

  /**
   * Returns the current state of the module.
   *
   * @return The current state of the module.
   */
  public SwerveModuleState getState() {
    return new SwerveModuleState(
        m_driveEncoder.getVelocity(), Rotation2d.fromRadians(m_integratedTurningEncoder.getPosition()));
  }

  /**
   * Returns the current position of the module.
   *
   * @return The current position of the module.
   */
  public SwerveModulePosition getPosition() {
    return new SwerveModulePosition(
        m_driveEncoder.getPosition(), Rotation2d.fromRadians(m_integratedTurningEncoder.getPosition()));
  }

  /**
   * Sets the desired state for the module.
   *
   * @param desiredState Desired state with speed and angle.
   */
  public void setDesiredState(SwerveModuleState desiredState) {
    // Optimize the reference state to avoid spinning further than 90 degrees
    SwerveModuleState state = optimizeModuleState(desiredState);

    // Calculate the turning motor output from the turning PID controller.
    m_driveMotor.setVoltage((state.speedMetersPerSecond / Constants.DriveConstants.kMaxVelocityMetersPerSecond) * 12);


    double currentAngleRadiansMod = m_integratedTurningEncoder.getPosition() % (2.0 * Math.PI);
    if (currentAngleRadiansMod < 0.0) {
      currentAngleRadiansMod += 2.0 * Math.PI;
    }

    // The reference angle has the range [0, 2pi) but the Neo's encoder can go above
    // that
    double adjustedReferenceAngleRadians = state.angle.getRadians() + m_integratedTurningEncoder.getPosition()
        - currentAngleRadiansMod;
    if (state.angle.getRadians() - currentAngleRadiansMod > Math.PI) {
      adjustedReferenceAngleRadians -= 2.0 * Math.PI;
    } else if (state.angle.getRadians() - currentAngleRadiansMod < -Math.PI) {
      adjustedReferenceAngleRadians += 2.0 * Math.PI;
    }

    pidController.setReference(adjustedReferenceAngleRadians, ControlType.kPosition);

    // pidController.setReference(0,ControlType.kPosition);
    // pidController.setReference(state.angle.getRadians(), ControlType.kPosition);
  }

  public SwerveModuleState optimizeModuleState(SwerveModuleState state) {
    return SwerveModuleState.optimize(state, Rotation2d.fromDegrees(m_integratedTurningEncoder.getPosition()));
  }

  double map(double x, double in_min, double in_max, double out_min, double out_max) {
    return (x - in_min) * (out_max - out_min) / (in_max - in_min) + out_min;
  }

  public void recalEncoders() {
    System.err.println("Setting " + turningEncoderId + " to " + m_absoluteEncoder.getAbsolutePosition());
    m_integratedTurningEncoder.setPosition(Units.degreesToRadians(m_absoluteEncoder.getAbsolutePosition()));
  }

  /** Zeroes all the SwerveModule encoders. */
  public void resetEncoders() {
    m_driveEncoder.setPosition(0);
    m_absoluteEncoder.setPosition(0);
  }
}
